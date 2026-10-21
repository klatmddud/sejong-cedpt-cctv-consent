package kr.ac.sejong.cedpt.cctvconsent.local;

import kr.ac.sejong.cedpt.cctvconsent.auth.SejongAuthException;
import kr.ac.sejong.cedpt.cctvconsent.auth.SejongStudent;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentAuthenticator;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * local 프로필 전용 학생 로그인. 세종대 포털 대신 테스트 계정 하나만 받는다.
 * 운영(local 프로필이 아닐 때)에는 이 빈이 없고 SejongPortalClient가 쓰인다.
 */
@Component
@Primary
@Profile("local")
class LocalStudentAuthenticator implements StudentAuthenticator {

	static final SejongStudent TEST_STUDENT = new SejongStudent("20990001", "테스트학생", "컴퓨터공학과", "4", "재학");
	static final String TEST_PASSWORD = "test1234";

	@Override
	public SejongStudent authenticate(String studentId, String password) {
		if (TEST_STUDENT.studentId().equals(studentId) && TEST_PASSWORD.equals(password)) {
			return TEST_STUDENT;
		}
		throw new SejongAuthException(SejongAuthException.Reason.INVALID_CREDENTIALS, "로컬 테스트 계정이 아닙니다.");
	}

}
