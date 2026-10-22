package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;

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
class EnrollmentSyncTest extends PostgresTestSupport {

	private static final String STUDENT = "20010001";

	@Autowired
	EnrollmentSync sync;

	@Autowired
	RosterImportService importService;

	@Autowired
	SemesterRepository semesters;

	@Autowired
	CourseRepository courses;

	@Autowired
	EnrollmentRepository enrollments;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.execute("TRUNCATE semester CASCADE");
	}

	@Test
	void createsSemesterCoursesAndEnrollmentsForTargetRooms() {
		sync.sync(STUDENT, "홍길동", 2026, "2", List.of(
				course("000304-001", "센B201"),
				course("000305-001", "호302/센B206"),
				course("000306-001", "센B209")));

		Semester semester = semesters.findByYearAndTerm(2026, "2").orElseThrow();
		assertThat(semester.getEndsOn()).isEqualTo(LocalDate.of(2027, 2, 28));
		assertThat(semester.getPurgeOn()).isEqualTo(LocalDate.of(2028, 2, 28));
		assertThat(enrollments.findBySemesterAndStudentId(semester, STUDENT))
				.extracting(e -> e.getCourse().getCode(), Enrollment::isInSjpt, Enrollment::isInLatestRoster,
						Enrollment::getStudentName)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple("000304-001", true, false, "홍길동"),
						org.assertj.core.groups.Tuple.tuple("000305-001", true, false, "홍길동"));
	}

	@Test
	void marksDroppedCourseWithoutTouchingRoster() {
		importRoster(FixtureCourse.of("000304-001", "센B201", STUDENT), FixtureCourse.of("000305-001", "센B202", STUDENT));
		sync.sync(STUDENT, "홍길동", 2026, "2", List.of(course("000304-001", "센B201"), course("000305-001", "센B202")));

		sync.sync(STUDENT, "홍길동", 2026, "2", List.of(course("000304-001", "센B201")));

		Semester semester = semesters.findByYearAndTerm(2026, "2").orElseThrow();
		Map<String, Enrollment> mine = enrollments.findBySemesterAndStudentId(semester, STUDENT).stream()
				.collect(Collectors.toMap(e -> e.getCourse().getCode(), e -> e));
		assertThat(mine.get("000304-001").isInSjpt()).isTrue();
		assertThat(mine.get("000305-001").isInSjpt()).isFalse();
		assertThat(mine.values()).allMatch(Enrollment::isInLatestRoster);
		// 명부로 정한 학기 종료일과 과목 정보는 그대로 둔다
		assertThat(semester.getEndsOn()).isEqualTo(LocalDate.of(2026, 12, 21));
		assertThat(courses.findBySemester(semester)).allMatch(c -> c.getName().equals("과목" + c.getCode()));
	}

	@Test
	void doesNotCreateSemesterWithoutTargetCourses() {
		sync.sync(STUDENT, "홍길동", 2026, "2", List.of(course("000306-001", "센B209")));

		assertThat(semesters.count()).isZero();
	}

	@Test
	void usesFirstTermEndDate() {
		assertThat(EnrollmentSync.defaultEndsOn(2026, "1")).isEqualTo(LocalDate.of(2026, 8, 31));
		assertThat(EnrollmentSync.defaultEndsOn(2027, "2")).isEqualTo(LocalDate.of(2028, 2, 29));
	}

	private void importRoster(FixtureCourse... rosterCourses) {
		byte[] xlsx = RosterWorkbookFixture.workbook("2026년도 2학기 출석부", 45, List.of(rosterCourses));
		importService.importRoster("명부.xlsx", new ByteArrayInputStream(xlsx), LocalDate.of(2026, 12, 21), List.of());
	}

	private static EnrollmentSync.EnrolledCourse course(String code, String room) {
		return new EnrollmentSync.EnrolledCourse(code, "sjpt" + code, "김교수", "월10:30-12:00", room);
	}

}
