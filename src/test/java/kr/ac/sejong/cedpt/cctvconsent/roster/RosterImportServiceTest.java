package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterWorkbookFixture.FixtureCourse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class RosterImportServiceTest extends PostgresTestSupport {

	private static final String TITLE = "2026년도 2학기 출석부";

	@Autowired
	RosterImportService importService;

	@Autowired
	SemesterRepository semesters;

	@Autowired
	EnrollmentRepository enrollments;

	@Autowired
	RosterUploadRepository uploads;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.execute("TRUNCATE semester CASCADE");
	}

	@Test
	void importsOnlyTargetRoomCourses() {
		RosterImportService.Result result = importRoster(LocalDate.of(2026, 12, 21),
				FixtureCourse.of("000304-001", "센B201", "20010001", "20010002"),
				FixtureCourse.of("000305-001", "호302/센B202", "20010002"),
				FixtureCourse.of("000306-001", "센B209", "20010003"),
				FixtureCourse.of("000307-001", null, "20010004"));

		assertThat(result).isEqualTo(new RosterImportService.Result(2026, "2", 2, 3, 2, 3, 0));
		Semester semester = semesters.findByYearAndTerm(2026, "2").orElseThrow();
		assertThat(semester.getPurgeOn()).isEqualTo(LocalDate.of(2027, 12, 21));
		assertThat(activeByCourse(semester)).containsOnly(
				Map.entry("000304-001", List.of("20010001", "20010002")),
				Map.entry("000305-001", List.of("20010002")));
	}

	@Test
	void reimportAddsNewStudentsAndKeepsRemovedOnes() {
		importRoster(LocalDate.of(2026, 12, 21),
				FixtureCourse.of("000304-001", "센B201", "20010001", "20010002"));

		RosterImportService.Result result = importRoster(LocalDate.of(2026, 12, 23),
				FixtureCourse.of("000304-001", "센B201", "20010002", "20010009"));

		assertThat(result.addedCount()).isEqualTo(1);
		assertThat(result.removedCount()).isEqualTo(1);
		Semester semester = semesters.findByYearAndTerm(2026, "2").orElseThrow();
		assertThat(semester.getEndsOn()).isEqualTo(LocalDate.of(2026, 12, 23));
		assertThat(semester.getPurgeOn()).isEqualTo(LocalDate.of(2027, 12, 23));

		Map<String, Boolean> inLatest = enrollments.findBySemester(semester).stream()
				.collect(Collectors.toMap(Enrollment::getStudentId, Enrollment::isInLatestRoster));
		assertThat(inLatest).containsOnly(
				Map.entry("20010001", false),
				Map.entry("20010002", true),
				Map.entry("20010009", true));
		assertThat(uploads.count()).isEqualTo(2);
	}

	@Test
	void countsReturningStudentAsAdded() {
		importRoster(LocalDate.of(2026, 12, 21), FixtureCourse.of("000304-001", "센B201", "20010001", "20010002"));
		importRoster(LocalDate.of(2026, 12, 21), FixtureCourse.of("000304-001", "센B201", "20010002"));

		RosterImportService.Result result = importRoster(LocalDate.of(2026, 12, 21),
				FixtureCourse.of("000304-001", "센B201", "20010001", "20010002"));

		assertThat(result.addedCount()).isEqualTo(1);
		assertThat(result.removedCount()).isZero();
		Semester semester = semesters.findByYearAndTerm(2026, "2").orElseThrow();
		assertThat(enrollments.findBySemester(semester)).hasSize(2).allMatch(Enrollment::isInLatestRoster);
	}

	@Test
	void rejectsRosterWithoutTargetRooms() {
		assertThatThrownBy(() -> importRoster(LocalDate.of(2026, 12, 21),
				FixtureCourse.of("000306-001", "센B209", "20010003")))
				.isInstanceOf(RosterParseException.class);
		assertThat(semesters.count()).isZero();
	}

	private RosterImportService.Result importRoster(LocalDate endsOn, FixtureCourse... courses) {
		byte[] xlsx = RosterWorkbookFixture.workbook(TITLE, 45, List.of(courses));
		return importService.importRoster("명부.xlsx", new ByteArrayInputStream(xlsx), endsOn);
	}

	private Map<String, List<String>> activeByCourse(Semester semester) {
		return enrollments.findBySemester(semester).stream()
				.filter(Enrollment::isInLatestRoster)
				.collect(Collectors.groupingBy(e -> e.getCourse().getCode(),
						Collectors.mapping(Enrollment::getStudentId, Collectors.toList())));
	}

}
