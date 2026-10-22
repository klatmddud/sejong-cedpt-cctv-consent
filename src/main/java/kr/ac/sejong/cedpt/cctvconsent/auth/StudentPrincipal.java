package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.io.Serializable;

/**
 * 로그인한 학생. 이름은 세종대 포털에서 확인한 값이다.
 *
 * @param coursesSynced 로그인 때 sjpt 수강 과목을 받아 반영했는지. 아니면 이전에 저장한 수강 정보로 보여 준다.
 */
public record StudentPrincipal(String studentId, String name, boolean coursesSynced) implements Serializable {

	public StudentPrincipal(String studentId, String name) {
		this(studentId, name, true);
	}

	@Override
	public String toString() {
		return studentId;
	}

}
