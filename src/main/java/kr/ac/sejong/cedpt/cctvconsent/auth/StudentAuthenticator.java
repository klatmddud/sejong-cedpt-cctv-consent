package kr.ac.sejong.cedpt.cctvconsent.auth;

/**
 * 학번과 비밀번호로 학생을 확인하고 현재 학기 수강 과목을 받는다.
 * 운영에서는 세종대 포털과 일반행정정보(SjptClient), local 프로필에서는 테스트 계정을 쓴다.
 */
public interface StudentAuthenticator {

	/**
	 * @throws SejongAuthException 확인 실패
	 */
	StudentLogin authenticate(String studentId, String password);

}
