package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterWorkbookFixture.FixtureCourse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class RosterQueryServiceTest extends PostgresTestSupport {

	@Autowired
	RosterQueryService queries;

	@Autowired
	RosterImportService importService;

	@Autowired
	SemesterRepository semesters;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.execute("TRUNCATE semester CASCADE");
	}

	@Test
	void listsStudentsWithoutConsentPerCourse() {
		byte[] xlsx = RosterWorkbookFixture.workbook(RosterTestFixtures.TITLE, 45, List.of(
				FixtureCourse.of("000304-001", "센B201", "20010002", "20010001", "20010003"),
				FixtureCourse.of("000305-001", "센B202", "20010001")));
		importService.importRoster(List.of(new RosterImportService.UploadedFile("명부.xlsx", xlsx)),
				List.of());
		sign("000304-001", "20010001");
		sign("000305-001", "20010001");
		// 명부에서 빠진 학생은 수강 중이 아니므로 미제출로 세지 않는다
		jdbc.update("update enrollment set in_latest_roster = false where student_id = '20010003'");

		long semesterId = semesters.findByYearAndTerm(2026, "2").orElseThrow().getId();
		List<RosterQueryService.CourseSummary> courses = queries.courses(semesterId);

		assertThat(courses).extracting(RosterQueryService.CourseSummary::code)
				.containsExactly("000304-001", "000305-001");
		RosterQueryService.CourseSummary first = courses.get(0);
		assertThat(first.studentCount()).isEqualTo(2);
		assertThat(first.unsubmittedCount()).isEqualTo(1);
		assertThat(first.unsubmitted()).extracting(RosterQueryService.StudentSummary::studentId)
				.containsExactly("20010002");
		assertThat(first.times()).containsExactly(
				new CourseTime(DayOfWeek.MONDAY, LocalTime.of(10, 30), LocalTime.of(12, 0)));
		assertThat(courses.get(1).unsubmitted()).isEmpty();
	}

	private void sign(String courseCode, String studentId) {
		jdbc.update("""
				insert into consent (enrollment_id, language, signer_name, signed_at)
				select e.id, 'KO', '홍길동', now() from enrollment e join course c on c.id = e.course_id
				where c.code = ? and e.student_id = ?""", courseCode, studentId);
	}

}
