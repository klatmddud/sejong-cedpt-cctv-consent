package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.util.List;

/**
 * 학생 로그인 결과. 수강 과목은 학생 확인과 같은 세션에서 조회한다 (비밀번호는 로그인 요청 안에서만 쓸 수 있음).
 *
 * @param term 수강 과목을 조회한 학기
 * @param courses 수강 과목. 학생 확인은 됐지만 과목 조회에 실패했으면 null
 */
public record StudentLogin(SejongStudent student, AcademicTerm term, List<SjptCourse> courses) {

	public boolean coursesLoaded() {
		return courses != null;
	}

}
