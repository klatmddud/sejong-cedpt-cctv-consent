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
	EnrollmentSync sync;

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

		assertThat(result).isEqualTo(new RosterImportService.Result(2026, "2", 2, 3, 2, 3, 0, 0, 0));
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
	void deletesSjptOnlyEnrollmentMissingFromRoster() {
		loginWith("20010001", "000304-001", "센B201");

		RosterImportService.Result result = importRoster(LocalDate.of(2026, 12, 21),
				FixtureCourse.of("000304-001", "센B201", "20010002"));

		assertThat(result.deletedCount()).isEqualTo(1);
		assertThat(result.deletedConsentCount()).isZero();
		Semester semester = semesters.findByYearAndTerm(2026, "2").orElseThrow();
		assertThat(enrollments.findBySemester(semester)).extracting(Enrollment::getStudentId).containsOnly("20010002");
	}

	@Test
	void requiresConfirmationBeforeDeletingConsents() {
		loginWith("20010001", "000304-001", "센B201");
		long enrollmentId = jdbc.queryForObject("select id from enrollment where student_id = '20010001'", Long.class);
		signConsent(enrollmentId);

		List<RosterImportService.ConsentDeletion> deletions = confirmationFor(
				FixtureCourse.of("000304-001", "센B201", "20010002"));
		assertThat(deletions).containsExactly(new RosterImportService.ConsentDeletion(
				enrollmentId, "20010001", "홍길동", "000304-001", "과목000304-001"));
		// 확인 전에는 아무것도 반영하지 않는다
		assertThat(jdbc.queryForObject("select count(*) from enrollment", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(1);
		assertThat(uploads.count()).isZero();

		RosterImportService.Result result = importRoster(LocalDate.of(2026, 12, 21), deletions,
				FixtureCourse.of("000304-001", "센B201", "20010002"));

		assertThat(result.deletedCount()).isEqualTo(1);
		assertThat(result.deletedConsentCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from enrollment where student_id = '20010001'", Integer.class))
				.isZero();
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from consent_document", Integer.class)).isZero();
		assertThat(uploads.findAll()).singleElement().satisfies(upload -> {
			assertThat(upload.getDeletedCount()).isEqualTo(1);
			assertThat(upload.getDeletedConsentCount()).isEqualTo(1);
		});
	}

	@Test
	void asksAgainWhenMoreConsentsWouldBeDeletedThanConfirmed() {
		loginWith("20010001", "000304-001", "센B201");
		signConsent(jdbc.queryForObject("select id from enrollment where student_id = '20010001'", Long.class));
		List<RosterImportService.ConsentDeletion> shown = confirmationFor(
				FixtureCourse.of("000304-001", "센B201", "20010002"));

		// 확인 화면을 보는 사이 다른 학생이 로그인해 동의서를 냈다
		loginWith("20010003", "000304-001", "센B201");
		signConsent(jdbc.queryForObject("select id from enrollment where student_id = '20010003'", Long.class));

		assertThatThrownBy(() -> importRoster(LocalDate.of(2026, 12, 21), shown,
				FixtureCourse.of("000304-001", "센B201", "20010002")))
				.isInstanceOfSatisfying(RosterImportService.ConfirmationRequiredException.class,
						ex -> assertThat(ex.getDeletions()).extracting(RosterImportService.ConsentDeletion::studentId)
								.containsExactlyInAnyOrder("20010001", "20010003"));
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(2);
	}

	@Test
	void neverDeletesEnrollmentThatWasInAnEarlierRoster() {
		importRoster(LocalDate.of(2026, 12, 21), FixtureCourse.of("000304-001", "센B201", "20010001", "20010002"));
		signConsent(jdbc.queryForObject("select id from enrollment where student_id = '20010001'", Long.class));
		importRoster(LocalDate.of(2026, 12, 21), FixtureCourse.of("000304-001", "센B201", "20010002"));

		RosterImportService.Result result = importRoster(LocalDate.of(2026, 12, 23),
				FixtureCourse.of("000304-001", "센B201", "20010002"));

		assertThat(result.deletedCount()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(1);
	}

	@Test
	void finalRosterEndsSjptConfirmationOfDroppedStudent() {
		importRoster(LocalDate.of(2026, 12, 21), FixtureCourse.of("000304-001", "센B201", "20010001", "20010002"));
		loginWith("20010001", "000304-001", "센B201");

		importRoster(LocalDate.of(2026, 12, 21), FixtureCourse.of("000304-001", "센B201", "20010002"));

		Semester semester = semesters.findByYearAndTerm(2026, "2").orElseThrow();
		assertThat(enrollments.findBySemesterAndStudentId(semester, "20010001")).singleElement()
				.satisfies(e -> {
					assertThat(e.isInLatestRoster()).isFalse();
					assertThat(e.isInSjpt()).isFalse();
				});
		assertThat(enrollments.countActiveBySemester(semester)).isEqualTo(1);
	}

	@Test
	void keepsSjptEnrollmentListedInRosterOrInCourseOutsideRoster() {
		loginWith("20010001", "000304-001", "센B201");
		loginWith("20010003", "000400-001", "센B203");

		RosterImportService.Result result = importRoster(LocalDate.of(2026, 12, 21),
				FixtureCourse.of("000304-001", "센B201", "20010001"));

		assertThat(result.deletedCount()).isZero();
		Semester semester = semesters.findByYearAndTerm(2026, "2").orElseThrow();
		assertThat(enrollments.findBySemester(semester))
				.extracting(e -> e.getStudentId() + "/" + e.getCourse().getCode() + "/" + e.isInLatestRoster()
						+ "/" + e.isInSjpt())
				.containsExactlyInAnyOrder("20010001/000304-001/true/true", "20010003/000400-001/false/true");
	}

	@Test
	void keepsEnrollmentFromPreviousRosterEvenIfOnlyConfirmedBySjpt() {
		importRoster(LocalDate.of(2026, 12, 21), FixtureCourse.of("000304-001", "센B201", "20010001", "20010002"));
		loginWith("20010001", "000304-001", "센B201");

		RosterImportService.Result result = importRoster(LocalDate.of(2026, 12, 21),
				FixtureCourse.of("000304-001", "센B201", "20010002"));

		assertThat(result.deletedCount()).isZero();
		assertThat(result.removedCount()).isEqualTo(1);
	}

	@Test
	void rejectsRosterWithoutTargetRooms() {
		assertThatThrownBy(() -> importRoster(LocalDate.of(2026, 12, 21),
				FixtureCourse.of("000306-001", "센B209", "20010003")))
				.isInstanceOf(RosterParseException.class);
		assertThat(semesters.count()).isZero();
	}

	private RosterImportService.Result importRoster(LocalDate endsOn, FixtureCourse... courses) {
		return importRoster(endsOn, List.of(), courses);
	}

	private RosterImportService.Result importRoster(LocalDate endsOn,
			List<RosterImportService.ConsentDeletion> confirmed, FixtureCourse... courses) {
		byte[] xlsx = RosterWorkbookFixture.workbook(TITLE, 45, List.of(courses));
		return importService.importRoster("명부.xlsx", new ByteArrayInputStream(xlsx), endsOn, confirmed);
	}

	private List<RosterImportService.ConsentDeletion> confirmationFor(FixtureCourse... courses) {
		try {
			importRoster(LocalDate.of(2026, 12, 21), courses);
		}
		catch (RosterImportService.ConfirmationRequiredException ex) {
			return ex.getDeletions();
		}
		throw new AssertionError("동의서 삭제 확인을 요구하지 않았습니다.");
	}

	private void loginWith(String studentId, String courseCode, String room) {
		sync.sync(studentId, "홍길동", 2026, "2", List.of(
				new EnrollmentSync.EnrolledCourse(courseCode, "sjpt" + courseCode, "김교수", "월10:30-12:00", room)));
	}

	private void signConsent(long enrollmentId) {
		long consentId = jdbc.queryForObject("""
				insert into consent (enrollment_id, language, signer_name, signed_at)
				values (?, 'KO', '홍길동', now()) returning id""", Long.class, enrollmentId);
		jdbc.update("insert into consent_document (consent_id, signature_png, pdf) values (?, ?, ?)",
				consentId, new byte[] {1}, new byte[] {1});
	}

	private Map<String, List<String>> activeByCourse(Semester semester) {
		return enrollments.findBySemester(semester).stream()
				.filter(Enrollment::isInLatestRoster)
				.collect(Collectors.groupingBy(e -> e.getCourse().getCode(),
						Collectors.mapping(Enrollment::getStudentId, Collectors.toList())));
	}

}
