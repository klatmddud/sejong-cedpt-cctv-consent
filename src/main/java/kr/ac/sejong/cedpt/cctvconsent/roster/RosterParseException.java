package kr.ac.sejong.cedpt.cctvconsent.roster;

/**
 * 수강명부 형식이 예상과 달라 신뢰할 수 없을 때. 메시지는 관리자 화면에 그대로 보여 준다.
 */
public class RosterParseException extends RuntimeException {

	public RosterParseException(String message) {
		super(message);
	}

	public RosterParseException(String message, Throwable cause) {
		super(message, cause);
	}

}
