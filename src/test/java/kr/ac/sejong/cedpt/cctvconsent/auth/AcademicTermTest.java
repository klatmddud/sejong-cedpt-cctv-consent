package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AcademicTermTest {

	@ParameterizedTest
	@CsvSource({
			"2026-01-15, 2025, 2",
			"2026-02-28, 2025, 2",
			"2026-03-01, 2026, 1",
			"2026-08-31, 2026, 1",
			"2026-09-01, 2026, 2",
			"2026-12-31, 2026, 2"
	})
	void picksTermByMonth(LocalDate date, int year, int term) {
		assertThat(AcademicTerm.of(date)).isEqualTo(new AcademicTerm(year, term));
	}

	@Test
	void usesSeoulDate() {
		// UTC 2026-02-28 16:00 = 서울 2026-03-01 01:00
		Clock clock = Clock.fixed(Instant.parse("2026-02-28T16:00:00Z"), ZoneOffset.UTC);

		assertThat(AcademicTerm.current(clock)).isEqualTo(new AcademicTerm(2026, 1));
	}

	@Test
	void mapsToSjptSemesterCode() {
		assertThat(new AcademicTerm(2026, 1).smtCd()).isEqualTo("10");
		assertThat(new AcademicTerm(2026, 2).smtCd()).isEqualTo("20");
		assertThat(new AcademicTerm(2026, 2).smtCdName()).isEqualTo("2학기");
	}

	@Test
	void rejectsSessionTerms() {
		assertThatThrownBy(() -> new AcademicTerm(2026, 3)).isInstanceOf(IllegalArgumentException.class);
	}

}
