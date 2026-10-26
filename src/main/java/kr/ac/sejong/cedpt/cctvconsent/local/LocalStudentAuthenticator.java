package kr.ac.sejong.cedpt.cctvconsent.local;

import java.time.Clock;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.auth.AcademicTerm;
import kr.ac.sejong.cedpt.cctvconsent.auth.SejongAuthException;
import kr.ac.sejong.cedpt.cctvconsent.auth.SejongStudent;
import kr.ac.sejong.cedpt.cctvconsent.auth.SjptCourse;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentAuthenticator;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentLogin;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * local 프로필 전용 학생 로그인. 세종대 포털 대신 테스트 계정만 받는다. TEST_STUDENT는 테스트 수업 2개를,
 * NO_COURSE_STUDENT는 수강 과목 없이 돌려준다(내 수업의 빈 화면 확인용).
 * 운영(local 프로필이 아닐 때)에는 이 빈이 없고 SjptClient가 쓰인다.
 */
@Component
@Primary
@Profile("local")
class LocalStudentAuthenticator implements StudentAuthenticator {

	static final SejongStudent TEST_STUDENT = new SejongStudent("20990001", "테스트학생");
	static final SejongStudent NO_COURSE_STUDENT = new SejongStudent("20990002", "수업없는학생");
	static final String TEST_PASSWORD = "test1234";
	static final List<SjptCourse> TEST_COURSES = List.of(
			testCourse("TEST", "001", "테스트 실습1", "월10:30-12:00(테스트교수/센B201)"),
			testCourse("TEST", "002", "테스트 실습2", "수13:30-15:00(테스트교수/센B206)"));

	@Override
	public StudentLogin authenticate(String studentId, String password) {
		if (TEST_STUDENT.studentId().equals(studentId) && TEST_PASSWORD.equals(password)) {
			return new StudentLogin(TEST_STUDENT, AcademicTerm.current(Clock.systemUTC()), TEST_COURSES);
		}
		if (NO_COURSE_STUDENT.studentId().equals(studentId) && TEST_PASSWORD.equals(password)) {
			return new StudentLogin(NO_COURSE_STUDENT, AcademicTerm.current(Clock.systemUTC()), List.of());
		}
		throw new SejongAuthException(SejongAuthException.Reason.INVALID_CREDENTIALS, "로컬 테스트 계정이 아닙니다.");
	}

	private static SjptCourse testCourse(String curiNo, String classNo, String name, String schedule) {
		return new SjptCourse(curiNo, classNo, curiNo + "-" + classNo, name, "컴퓨터공학과", "전공필수", "3.0", schedule,
				"", "대학");
	}

}
