package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CourseTimeTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"금09:00-12:00                  | FRIDAY 09:00-12:00",
			"월수13:30-15:00                 | MONDAY 13:30-15:00, WEDNESDAY 13:30-15:00",
			"월10:30-12:00 수10:30-12:00     | MONDAY 10:30-12:00, WEDNESDAY 10:30-12:00",
			"화13:00-15:00,목9:00-10:30      | TUESDAY 13:00-15:00, THURSDAY 09:00-10:30"
	})
	void splitsScheduleIntoDays(String schedule, String expected) {
		assertThat(CourseTime.parse(schedule))
				.extracting(time -> time.day() + " " + time.start() + "-" + time.end())
				.containsExactly(expected.split(", "));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {"미정", "''", "월10:30-12:00(김교수/센B201)", "월15:00-13:00", "월25:00-26:00"})
	void returnsNothingForUnknownSchedule(String schedule) {
		assertThat(CourseTime.parse(schedule)).isEmpty();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {"null"}, nullValues = "null")
	void returnsNothingForMissingSchedule(String schedule) {
		assertThat(CourseTime.parse(schedule)).isEqualTo(List.of());
	}

}
