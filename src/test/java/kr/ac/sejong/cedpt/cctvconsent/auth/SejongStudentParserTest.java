package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SejongStudentParserTest {

	private static final String STATUS_PAGE = """
			<html><body>
			<div class="b-con-box">
			  <h4 class="b-h4-tit01">사용자 정보</h4>
			  <table class="b-board-table">
			    <tbody>
			      <tr><th>학과명</th><td> 컴퓨터공학과 </td></tr>
			      <tr><th>학번</th><td>20011234</td></tr>
			      <tr><th>이름</th><td>홍길동</td></tr>
			      <tr><th>학년</th><td>3</td></tr>
			      <tr><th>사용자 상태</th><td>재학</td></tr>
			    </tbody>
			  </table>
			</div>
			<div class="b-con-box">
			  <h4 class="b-h4-tit01">인증 현황</h4>
			  <table class="b-board-table">
			    <tbody><tr><th>학번</th><td>99999999</td></tr></tbody>
			  </table>
			</div>
			</body></html>
			""";

	@Test
	void parsesUserInfoTable() {
		SejongStudent student = SejongStudentParser.parse(STATUS_PAGE);

		assertThat(student).isEqualTo(new SejongStudent("20011234", "홍길동", "컴퓨터공학과", "3", "재학"));
	}

	@Test
	void failsWhenUserInfoTableIsMissing() {
		assertThatThrownBy(() -> SejongStudentParser.parse("<html><body>로그인이 필요합니다</body></html>"))
				.isInstanceOfSatisfying(SejongAuthException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(SejongAuthException.Reason.UNEXPECTED_RESPONSE));
	}

}
