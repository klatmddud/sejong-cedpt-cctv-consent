package kr.ac.sejong.cedpt.cctvconsent.auth;

/**
 * 학번과 비밀번호로 학생을 확인한다. 운영에서는 세종대 포털(SejongPortalClient), local 프로필에서는 테스트 계정을 쓴다.
 */
public interface StudentAuthenticator {

	/**
	 * @throws SejongAuthException 확인 실패
	 */
	SejongStudent authenticate(String studentId, String password);

}
