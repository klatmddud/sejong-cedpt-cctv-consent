package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.io.Serializable;

/** 로그인한 학생. 이름은 세종대 포털에서 확인한 값이다. */
public record StudentPrincipal(String studentId, String name) implements Serializable {

	@Override
	public String toString() {
		return studentId;
	}

}
