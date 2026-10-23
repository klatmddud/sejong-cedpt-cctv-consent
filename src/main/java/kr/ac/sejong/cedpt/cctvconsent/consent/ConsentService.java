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
	private static final int MAX_COURSES_PER_SUBMIT = 30;

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

	/**
	 * 선택한 수업들에 같은 서명으로 동의서를 한 번에 제출한다. 하나라도 문제가 있으면 아무것도 저장하지 않는다.
	 *
	 * @param signerName 세종대 포털 인증으로 확인한 이름
	 * @param shownLanguage 학생이 읽은 동의서 언어. 저장하는 PDF는 언어와 관계없이 항상 한글본이다.
	 * @return 제출한 수업 수
	 */
	@Transactional
	public int submit(String studentId, String signerName, List<Long> enrollmentIds, ConsentLanguage shownLanguage,
			boolean agreed, String signatureDataUrl) {
		List<Long> ids = enrollmentIds == null ? List.of() : enrollmentIds.stream().distinct().toList();
		if (ids.isEmpty()) {
			throw new ConsentException("consent.error.noCourse");
		}
		// 한 학생이 듣는 대상 수업은 몇 개뿐이다. 조작된 요청이 수강마다 쿼리를 수만 번 돌리지 못하게 막는다
		if (ids.size() > MAX_COURSES_PER_SUBMIT) {
			throw new ConsentException("consent.error.noCourse");
		}
		List<Enrollment> selected = enrollments.lockOpenByIds(ids, studentId, today());
		if (selected.size() != ids.size()) {
			throw new NotFoundException();
		}
		if (!consents.findByEnrollmentIn(selected).isEmpty()) {
			throw new ConsentException("consent.error.already");
		}
		if (!agreed) {
			throw new ConsentException("consent.error.notAgreed");
		}
		byte[] signature = SignatureImage.fromDataUrl(signatureDataUrl);

		OffsetDateTime signedAt = OffsetDateTime.now(SEOUL);
		// 동의서 양식에는 수업 정보가 없으므로 선택한 수업 모두 같은 PDF를 갖는다
		byte[] pdf = renderer.render(ConsentLanguage.KO, signerName, signedAt.atZoneSameInstant(SEOUL), signature);
		try {
			for (Enrollment enrollment : selected) {
				Consent consent = consents.saveAndFlush(new Consent(enrollment, shownLanguage, signerName, signedAt));
				documents.save(new ConsentDocument(consent, signature, pdf));
			}
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 수업을 동시에 두 번 제출한 경우
			throw new ConsentException("consent.error.already");
		}
		return selected.size();
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
