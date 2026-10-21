package kr.ac.sejong.cedpt.cctvconsent.consent;

/** 학생에게 보여 줄 동의 제출 오류. 메시지는 messages.properties의 코드다. */
public class ConsentException extends RuntimeException {

	public ConsentException(String messageCode) {
		super(messageCode);
	}

}
