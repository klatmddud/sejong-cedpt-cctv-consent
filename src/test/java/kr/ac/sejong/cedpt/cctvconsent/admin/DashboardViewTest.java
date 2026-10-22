package kr.ac.sejong.cedpt.cctvconsent.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.roster.CourseTime;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.CourseSummary;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.SemesterSummary;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.SjptOnlyEnrollment;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.StudentSummary;
import kr.ac.sejong.cedpt.cctvconsent.roster.TargetRooms;
import org.junit.jupiter.api.Test;

class DashboardViewTest {

	private static final TargetRooms ROOMS = new TargetRooms("B201,B202,B203,B206,B207");
	private static final SemesterSummary SEMESTER = new SemesterSummary(1L, 2026, "2", LocalDate.of(2026, 12, 31),
			LocalDate.of(2027, 12, 31), 3, 5);
	private static final SjptOnlyEnrollment SJPT_ONLY = new SjptOnlyEnrollment("D", "학생D", "타과과목", "009999-001",
			"[담당교수]", "센B206", "목10:00-12:00");

	@Test
	void summarizesRatesPendingStudentsAndChecks() {
		DashboardView view = DashboardView.of(SEMESTER, List.of(
				course("000304-001", "센B201", "월10:00-12:00", student("A", true, true), student("B", false, false),
						student("E", true, false)),
				course("000305-001", "센B201", "화10:00-12:00", student("A", true, true), student("C", true, true),
						student("D", false, false)),
				course("000306-001", "센B203", "미정", student("B", false, false), student("D", false, true),
						student("E", false, false))), ROOMS::matching, List.of(SJPT_ONLY), null);

		assertThat(view.total()).isEqualTo(9);
		assertThat(view.submitted()).isEqualTo(4);
		assertThat(view.rate()).isEqualTo(44);
		// B는 두 강의에서 미제출이지만 한 명으로 센다
		assertThat(view.progress().pending()).isEqualTo(3);
		assertThat(view.progress().total()).isEqualTo(5);
		// B는 로그인 확인 안 됨. D는 한 강의만 sjpt에서 확인되어도, E는 sjpt에 없어도 동의서를 냈으므로 로그인한 학생
		assertThat(view.progress()).isEqualTo(new DashboardView.StudentProgress(1, 2, 2));
		assertThat(view.rooms()).extracting(r -> r.room() + "/" + r.rate()).containsExactly("센B201/66", "센B203/0");
		assertThat(view.lagging()).extracting(c -> c.course().code() + "/" + c.unsubmitted() + "/" + c.rate())
				.containsExactly("000306-001/3/0", "000304-001/1/66", "000305-001/1/66");
		assertThat(view.unscheduled()).extracting(CourseSummary::code).containsExactly("000306-001");
		assertThat(view.sjptOnly()).containsExactly(SJPT_ONLY);
	}

	private static StudentSummary student(String id, boolean submitted, boolean inSjpt) {
		return new StudentSummary(id, "학생" + id, submitted, inSjpt);
	}

	private static CourseSummary course(String code, String room, String schedule, StudentSummary... students) {
		return new CourseSummary(code, "과목" + code, "[담당교수]", schedule, room, students.length,
				CourseTime.parse(schedule), List.of(students), students.length, 0);
	}

}
