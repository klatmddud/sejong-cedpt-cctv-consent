package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 과목을 지운다(폐강 등 명부에서 통째로 빠진 과목 정리용).
 * 수강과 동의서는 ON DELETE CASCADE로 함께 삭제된다.
 *
 * 같은 과목이 다시 들어오면 새로 만들어진다: 그 과목이 든 명부를 올리거나, sjpt에 그 과목이 남아 있는 학생이 로그인할 때.
 */
@Service
public class CourseDeletionService {

	private final SemesterRepository semesters;
	private final CourseRepository courses;
	private final EnrollmentRepository enrollments;

	public CourseDeletionService(SemesterRepository semesters, CourseRepository courses,
			EnrollmentRepository enrollments) {
		this.semesters = semesters;
		this.courses = courses;
		this.enrollments = enrollments;
	}

	public record Deleted(String code, String name, int enrollmentCount, int consentCount) {
	}

	/** 지울 과목이 없다. 다른 관리자가 먼저 지웠을 수 있다. */
	public static class CourseNotFoundException extends RuntimeException {

		CourseNotFoundException(String code) {
			super("과목 " + code + "을(를) 찾을 수 없습니다.");
		}

	}

	/** 확인한 뒤 동의서 수가 바뀌었다. 이 예외로 트랜잭션이 롤백되어 아무것도 지워지지 않는다. */
	public static class ConsentCountChangedException extends RuntimeException {

		ConsentCountChangedException(int confirmed, int actual) {
			super("확인한 동의서는 " + confirmed + "건인데 지금은 " + actual + "건입니다. 다시 확인하세요.");
		}

	}

	/**
	 * @param confirmedConsentCount 관리자가 확인 화면에서 본 동의서 수
	 */
	@Transactional
	public Deleted delete(long semesterId, String code, int confirmedConsentCount) {
		Semester semester = semesters.findById(semesterId).orElseThrow(() -> new CourseNotFoundException(code));
		// 명부 업로드, 학생 로그인 동기화와 겹치지 않도록 같은 학기 행을 잠근다
		semesters.findForUpdate(semester.getYear(), semester.getTerm());
		Course course = courses.findBySemesterAndCode(semester, code)
				.orElseThrow(() -> new CourseNotFoundException(code));

		// 잠가 두면 확인하는 사이 학생이 동의서를 내도 세기 전에 끝나거나 삭제 뒤로 밀린다
		List<Enrollment> locked = enrollments.lockByCourse(course);
		int signed = locked.isEmpty() ? 0 : enrollments.findSignedIds(locked.stream().map(Enrollment::getId).toList()).size();
		if (signed != confirmedConsentCount) {
			throw new ConsentCountChangedException(confirmedConsentCount, signed);
		}
		Deleted deleted = new Deleted(course.getCode(), course.getName(), locked.size(), signed);
		courses.deleteWithEnrollments(course.getId());
		return deleted;
	}

}
