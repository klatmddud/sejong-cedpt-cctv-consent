package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SemesterPurgeTest extends PostgresTestSupport {

	@Autowired
	SemesterPurge purge;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE semester CASCADE");
		long expired = semester(2025, "1", "2026-06-30");
		semester(2026, "2", "2027-12-31");
		long courseId = jdbc.queryForObject(
				"insert into course (semester_id, code, name, room) values (?, '000304-001', '과목', '센B201') returning id",
				Long.class, expired);
		long enrollmentId = jdbc.queryForObject("""
				insert into enrollment (course_id, student_id, student_name, in_latest_roster)
				values (?, '20010001', '홍길동', true) returning id""", Long.class, courseId);
		long consentId = jdbc.queryForObject("""
				insert into consent (enrollment_id, language, signer_name, signed_at)
				values (?, 'KO', '홍길동', now()) returning id""", Long.class, enrollmentId);
		jdbc.update("insert into consent_document (consent_id, signature_png, pdf) values (?, ?, ?)",
				consentId, new byte[] {1}, new byte[] {1});
	}

	@Test
	void deletesSemestersFromPurgeDateWithTheirConsents() {
		assertThat(purge.purgeExpired(LocalDate.of(2026, 6, 29))).isZero();

		assertThat(purge.purgeExpired(LocalDate.of(2026, 6, 30))).isEqualTo(1);

		assertThat(jdbc.queryForList("select year from semester", Integer.class)).containsExactly(2026);
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from consent_document", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from enrollment", Integer.class)).isZero();
	}

	private long semester(int year, String term, String purgeOn) {
		return jdbc.queryForObject("""
				insert into semester (year, term, ends_on, purge_on, created_at)
				values (?, ?, ?::date - interval '1 year', ?::date, now()) returning id""",
				Long.class, year, term, purgeOn, purgeOn);
	}

}
