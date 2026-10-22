package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterWorkbookFixture.FixtureCourse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CourseDeletionServiceTest extends PostgresTestSupport {

	@Autowired
	CourseDeletionService deletion;

	@Autowired
	RosterImportService importService;

	@Autowired
	RosterQueryService queries;

	@Autowired
	SemesterRepository semesters;

	@Autowired
	JdbcTemplate jdbc;

	long semesterId;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE semester CASCADE");
		byte[] xlsx = RosterWorkbookFixture.workbook(RosterTestFixtures.TITLE, 45, List.of(
				FixtureCourse.of("000304-001", "센B201", "20010001", "20010002"),
				FixtureCourse.of("000305-001", "센B202", "20010001")));
		importService.importRoster(importService.read(List.of(new RosterImportService.UploadedFile("명부.xlsx", xlsx))),
				List.of());
		semesterId = semesters.findByYearAndTerm(2026, "2").orElseThrow().getId();
		sign("000304-001", "20010001");
		sign("000305-001", "20010001");
	}

	@Test
	void deletesCourseWithItsEnrollmentsAndConsents() {
		CourseDeletionService.Deleted deleted = deletion.delete(semesterId, "000304-001", 1);

		assertThat(deleted).isEqualTo(new CourseDeletionService.Deleted("000304-001", "과목000304-001", 2, 1));
		assertThat(queries.courses(semesterId)).extracting(RosterQueryService.CourseSummary::code)
				.containsExactly("000305-001");
		assertThat(jdbc.queryForObject("select count(*) from enrollment", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from consent_document", Integer.class)).isEqualTo(1);
	}

	@Test
	void refusesWhenConsentCountChangedSinceConfirmation() {
		assertThatThrownBy(() -> deletion.delete(semesterId, "000304-001", 0))
				.isInstanceOf(CourseDeletionService.ConsentCountChangedException.class);

		assertThat(queries.courses(semesterId)).hasSize(2);
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(2);
	}

	@Test
	void refusesUnknownCourse() {
		assertThatThrownBy(() -> deletion.delete(semesterId, "999999-001", 0))
				.isInstanceOf(CourseDeletionService.CourseNotFoundException.class);
	}

	@Test
	void showsCountsThatDeletionWouldRemove() {
		RosterQueryService.CourseSummary course = queries.courses(semesterId).get(0);

		assertThat(course.enrollmentCount()).isEqualTo(2);
		assertThat(course.consentCount()).isEqualTo(1);
	}

	private void sign(String courseCode, String studentId) {
		long consentId = jdbc.queryForObject("""
				insert into consent (enrollment_id, language, signer_name, signed_at)
				select e.id, 'KO', '홍길동', now() from enrollment e join course c on c.id = e.course_id
				where c.code = ? and e.student_id = ? returning id""", Long.class, courseCode, studentId);
		jdbc.update("insert into consent_document (consent_id, signature_png, pdf) values (?, ?, ?)",
				consentId, new byte[] {1}, new byte[] {1});
	}

}
