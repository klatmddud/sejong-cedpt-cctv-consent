package kr.ac.sejong.cedpt.cctvconsent.consent;

/** 학생에게 그대로 보여 줄 수 있는 동의 제출 오류. */
public class ConsentException extends RuntimeException {

	public ConsentException(String message) {
		super(message);
	}

}
