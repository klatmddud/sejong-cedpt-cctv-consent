package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterWorkbookFixture.FixtureCourse;

/**
 * 다른 패키지 테스트에서 가짜 수강명부를 만들 때 쓰는 입구.
 */
public final class RosterTestFixtures {

	public static final String TITLE = "2026년도 2학기 출석부";

	private RosterTestFixtures() {
	}

	public static Object course(String code, String room, String... studentIds) {
		return FixtureCourse.of(code, room, studentIds);
	}

	public static byte[] roster(List<Object> courses) {
		return roster(TITLE, courses);
	}

	public static byte[] roster(String title, List<Object> courses) {
		return RosterWorkbookFixture.workbook(title, 45, courses.stream().map(FixtureCourse.class::cast).toList());
	}

}
