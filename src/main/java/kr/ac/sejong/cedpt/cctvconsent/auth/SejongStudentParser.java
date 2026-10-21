package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.util.HashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * classic.sejong.ac.kr 고전독서인증현황 페이지의 "사용자 정보" 표를 파싱한다.
 */
final class SejongStudentParser {

	private static final String USER_INFO_ROWS =
			".b-con-box:has(h4.b-h4-tit01:contains(사용자 정보)) table.b-board-table tbody tr";

	private SejongStudentParser() {
	}

	static SejongStudent parse(String html) {
		Map<String, String> fields = new HashMap<>();
		for (Element row : Jsoup.parse(html).select(USER_INFO_ROWS)) {
			String label = row.select("th").text().trim();
			if (!label.isEmpty()) {
				fields.put(label, row.select("td").text().trim());
			}
		}

		String studentId = fields.getOrDefault("학번", "");
		String name = fields.getOrDefault("이름", "");
		if (studentId.isEmpty() || name.isEmpty()) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"사용자 정보 표에서 학번/이름을 찾을 수 없습니다.");
		}
		return new SejongStudent(
				studentId,
				name,
				fields.getOrDefault("학과명", ""),
				fields.getOrDefault("학년", ""),
				fields.getOrDefault("사용자 상태", ""));
	}

}
