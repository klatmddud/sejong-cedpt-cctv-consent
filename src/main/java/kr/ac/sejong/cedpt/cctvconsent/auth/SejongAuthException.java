package kr.ac.sejong.cedpt.cctvconsent.auth;

public class SejongAuthException extends RuntimeException {

	public enum Reason {
		/** 학번 또는 비밀번호가 틀림 */
		INVALID_CREDENTIALS,
		/** 포털 계정이 잠겼거나 사용 기간 만료 등으로 로그인 불가 */
		ACCOUNT_RESTRICTED,
		/** 세종대 서버 연결 실패, 타임아웃, 5xx 응답 */
		UNAVAILABLE,
		/** 응답 형식이 예상과 다름 (세종대 사이트 구조 변경 가능성) */
		UNEXPECTED_RESPONSE
	}

	private final Reason reason;

	public SejongAuthException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public SejongAuthException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}

}
