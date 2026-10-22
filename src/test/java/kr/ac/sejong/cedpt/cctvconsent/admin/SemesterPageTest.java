package kr.ac.sejong.cedpt.cctvconsent.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.roster.CourseTime;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.CourseSummary;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.StudentSummary;
import kr.ac.sejong.cedpt.cctvconsent.roster.TargetRooms;
import org.junit.jupiter.api.Test;

class SemesterPageTest {

	private static final TargetRooms ROOMS = new TargetRooms("B201,B202,B203,B206,B207");

	@Test
	void groupsCoursesByRoomAndCountsUnsubmitted() {
		SemesterPage page = page(List.of(
				course("000304-001", "센B201", "월수13:30-15:00", 30, 2),
				course("000305-001", "센B201", "화10:00-12:00", 20, 0),
				course("000306-001", "센B203", "금09:00-12:00", 40, 5)), null);

		assertThat(page.groups()).extracting(g -> g.room() + "/" + g.courses().size() + "/" + g.studentCount()
				+ "/" + g.unsubmittedCount())
				.containsExactly("센B201/2/50/2", "센B203/1/40/5");
		assertThat(page.unsubmittedCount()).isEqualTo(7);
	}

	@Test
	void placesCoursesOfChosenRoomOnWeekdayTimetable() {
		SemesterPage page = page(List.of(
				course("000304-001", "센B201", "월수13:30-15:00", 30, 0),
				course("000306-001", "센B203", "금09:00-12:00", 40, 0)), "센B203");

		assertThat(page.room()).isEqualTo("센B203");
		assertThat(page.hours()).hasSize(11).startsWith("09:00").endsWith("19:00");
		assertThat(page.timetableHeight()).isEqualTo(22 * SemesterPage.SLOT_PX);
		assertThat(page.days()).extracting(SemesterPage.TimetableDay::name).containsExactly("월", "화", "수", "목", "금");
		SemesterPage.Block block = page.days().get(4).blocks().get(0);
		assertThat(block.course().code()).isEqualTo("000306-001");
		assertThat(block.top()).isEqualTo(2);
		assertThat(block.height()).isEqualTo(6 * SemesterPage.SLOT_PX - 4);
	}

	@Test
	void widensTimetableForEarlyLateAndWeekendCourses() {
		SemesterPage page = page(List.of(
				course("000304-001", "센B201", "토08:30-10:00", 10, 0),
				course("000305-001", "센B201", "월20:00-21:30", 10, 0)), "없는 강의실");

		assertThat(page.room()).isEqualTo("센B201");
		assertThat(page.hours()).startsWith("08:00").endsWith("21:00");
		assertThat(page.days()).extracting(SemesterPage.TimetableDay::name).endsWith("금", "토");
		assertThat(page.days().get(5).blocks().get(0).top()).isEqualTo(SemesterPage.SLOT_PX + 2);
	}

	@Test
	void placesOverlappingCoursesSideBySide() {
		SemesterPage page = page(List.of(
				course("000304-001", "센B201", "월10:00-12:00", 10, 0),
				course("000305-001", "센B201", "월10:30-12:00", 10, 0),
				course("000306-001", "센B201", "월12:00-13:00", 10, 0)), null);

		assertThat(page.days().get(0).blocks())
				.extracting(b -> b.course().code() + "/" + b.left() + "/" + b.width())
				.containsExactly("000304-001/0.0/50.0", "000305-001/50.0/50.0", "000306-001/0.0/100.0");
	}

	@Test
	void endsTimetableAtMidnightForLateCourses() {
		SemesterPage page = page(List.of(course("000304-001", "센B201", "목21:00-23:30", 10, 0)), null);

		assertThat(page.hours()).startsWith("09:00").endsWith("23:00").hasSize(15);
		assertThat(page.timetableHeight()).isEqualTo(30 * SemesterPage.SLOT_PX);
		SemesterPage.Block block = page.days().get(3).blocks().get(0);
		assertThat(block.top() + block.height() + 4).isEqualTo(29 * SemesterPage.SLOT_PX + 2);
	}

	@Test
	void putsCourseOfSeveralRoomsUnderEachTargetRoom() {
		SemesterPage page = page(List.of(
				course("000304-001", "센B201/센B202", "월10:00-12:00", 10, 3),
				course("000305-001", "호302/센B202", "화10:00-12:00", 10, 1),
				course("000306-001", "센B209", "수10:00-12:00", 10, 0)), "센B202");

		assertThat(page.groups()).extracting(g -> g.room() + "/" + g.courses().size() + "/" + g.unsubmittedCount())
				.containsExactly("센B201/1/3", "센B202/2/4", "센B209/1/0");
		assertThat(page.unsubmittedCount()).isEqualTo(4);
		assertThat(page.courses()).hasSize(3);
		assertThat(page.days().get(0).blocks()).extracting(b -> b.course().code()).containsExactly("000304-001");
	}

	@Test
	void groupsSameRoomWrittenDifferently() {
		SemesterPage page = page(List.of(
				course("000304-001", "센B201", "월10:00-12:00", 10, 1),
				course("000305-001", "B201", "화10:00-12:00", 10, 2),
				course("000306-001", "센b201/센B202", "수10:00-12:00", 10, 0)), "센B201");

		assertThat(page.groups()).extracting(g -> g.room() + "/" + g.courses().size())
				.containsExactly("센B201/3", "센B202/1");
		assertThat(page.days()).extracting(day -> day.blocks().size()).startsWith(1, 1, 1);
	}

	@Test
	void listsCoursesWithUnreadableScheduleSeparately() {
		SemesterPage page = page(List.of(course("000304-001", "센B201", "미정", 10, 0)), null);

		assertThat(page.unscheduled()).extracting(CourseSummary::code).containsExactly("000304-001");
		assertThat(page.days()).allMatch(day -> day.blocks().isEmpty());
	}

	@Test
	void hasNoRoomWithoutCourses() {
		assertThat(page(List.of(), null).room()).isNull();
	}

	private static SemesterPage page(List<CourseSummary> courses, String room) {
		return SemesterPage.of(courses, room, ROOMS::matching);
	}

	private static CourseSummary course(String code, String room, String schedule, long students, int unsubmitted) {
		List<StudentSummary> missing = java.util.stream.IntStream.range(0, unsubmitted)
				.mapToObj(i -> new StudentSummary("2001000" + i, "학생" + i, false))
				.toList();
		return new CourseSummary(code, "과목" + code, "김교수", schedule, room, students, CourseTime.parse(schedule),
				missing, students, 0);
	}

}
