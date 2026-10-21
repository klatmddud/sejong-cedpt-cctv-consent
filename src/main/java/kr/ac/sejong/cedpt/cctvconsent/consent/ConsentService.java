package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.sejong.cedpt.cctvconsent.roster.Course;
import kr.ac.sejong.cedpt.cctvconsent.roster.Enrollment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 학생 본인의 동의서 조회와 제출. 학생은 최신 명부에 있고 학기가 끝나지 않은 자기 수강에만 접근할 수 있다.
 */
@Service
public class ConsentService {

	public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	public record CourseConsent(long enrollmentId, String semester, String courseCode, String courseName,
			String professor, String schedule, String room, OffsetDateTime signedAt, ConsentLanguage language) {

		public boolean signed() {
			return signedAt != null;
		}

	}

	public record PdfFile(String fileName, byte[] content) {
	}

	@ResponseStatus(HttpStatus.NOT_FOUND)
	public static class NotFoundException extends RuntimeException {
	}

	private final StudentEnrollmentRepository enrollments;
	private final ConsentRepository consents;
	private final ConsentDocumentRepository documents;
	private final ConsentPdfRenderer renderer;

	ConsentService(StudentEnrollmentRepository enrollments, ConsentRepository consents,
			ConsentDocumentRepository documents, ConsentPdfRenderer renderer) {
		this.enrollments = enrollments;
		this.consents = consents;
		this.documents = documents;
		this.renderer = renderer;
	}

	@Transactional(readOnly = true)
	public List<CourseConsent> courses(String studentId) {
		List<Enrollment> open = enrollments.findOpen(studentId, today());
		Map<Long, Consent> byEnrollment = consents.findByEnrollmentIn(open).stream()
				.collect(Collectors.toMap(consent -> consent.getEnrollment().getId(), Function.identity()));
		return open.stream().map(enrollment -> toView(enrollment, byEnrollment.get(enrollment.getId()))).toList();
	}

	@Transactional(readOnly = true)
	public CourseConsent course(String studentId, long enrollmentId) {
		Enrollment enrollment = openEnrollment(studentId, enrollmentId);
		return toView(enrollment, consents.findByEnrollment(enrollment).orElse(null));
	}

	/**
	 * @param signerName 세종대 포털 인증으로 확인한 이름
	 */
	@Transactional
	public void submit(String studentId, String signerName, long enrollmentId, ConsentLanguage language,
			boolean agreed, String signatureDataUrl) {
		Enrollment enrollment = openEnrollment(studentId, enrollmentId);
		if (consents.findByEnrollment(enrollment).isPresent()) {
			throw new ConsentException("이미 제출한 동의서입니다.");
		}
		if (!agreed) {
			throw new ConsentException("'동의함'에 체크하세요.");
		}
		byte[] signature = SignatureImage.fromDataUrl(signatureDataUrl);

		OffsetDateTime signedAt = OffsetDateTime.now(SEOUL);
		byte[] pdf = renderer.render(language, signerName, signedAt.atZoneSameInstant(SEOUL), signature);
		try {
			Consent consent = consents.saveAndFlush(new Consent(enrollment, language, signerName, signedAt));
			documents.save(new ConsentDocument(consent, signature, pdf));
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 수업을 동시에 두 번 제출한 경우
			throw new ConsentException("이미 제출한 동의서입니다.");
		}
	}

	@Transactional(readOnly = true)
	public PdfFile pdf(String studentId, long enrollmentId) {
		Enrollment enrollment = openEnrollment(studentId, enrollmentId);
		Consent consent = consents.findByEnrollment(enrollment).orElseThrow(NotFoundException::new);
		byte[] pdf = documents.findById(consent.getId()).orElseThrow(NotFoundException::new).getPdf();
		return new PdfFile("CCTV동의서_" + enrollment.getCourse().getName() + ".pdf", pdf);
	}

	private Enrollment openEnrollment(String studentId, long enrollmentId) {
		return enrollments.findOpenById(enrollmentId, studentId, today()).orElseThrow(NotFoundException::new);
	}

	private static CourseConsent toView(Enrollment enrollment, Consent consent) {
		Course course = enrollment.getCourse();
		return new CourseConsent(enrollment.getId(),
				course.getSemester().getYear() + "-" + course.getSemester().getTerm() + "학기",
				course.getCode(), course.getName(), course.getProfessor(), course.getSchedule(), course.getRoom(),
				consent == null ? null : consent.getSignedAt(), consent == null ? null : consent.getLanguage());
	}

	private static LocalDate today() {
		return LocalDate.now(SEOUL);
	}

}
