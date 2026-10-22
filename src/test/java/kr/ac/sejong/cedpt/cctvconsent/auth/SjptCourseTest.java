package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SjptCourseTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"목15:00-18:00(문현준/센819)                                     | 목15:00-18:00             | 문현준        | 센819",
			"월10:30-12:00(김교수/호302),수10:30-12:00(김교수/센B205)          | 월10:30-12:00 수10:30-12:00 | 김교수        | 호302/센B205",
			"화13:30-15:00(김교수/이교수/센B201) 목13:30-15:00(김교수/이교수/센B201) | 화13:30-15:00 목13:30-15:00 | 김교수, 이교수 | 센B201",
			"수금15:00-16:30(센B201)                                          | 수금15:00-16:30            | ''           | 센B201"
	})
	void parsesTimetable(String schedule, String times, String professors, String rooms) {
		SjptCourse course = course(schedule);

		assertThat(course.times()).isEqualTo(times);
		assertThat(course.professors()).isEqualTo(professors);
		assertThat(course.rooms()).isEqualTo(rooms);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {"미정 | 미정", "'' | ''"})
	void keepsUnknownTimetableAsIs(String schedule, String times) {
		SjptCourse course = course(schedule);

		assertThat(course.times()).isEqualTo(times);
		assertThat(course.rooms()).isEmpty();
	}

	private static SjptCourse course(String schedule) {
		return new SjptCourse("000304", "001", "000304-001", "과목", "컴퓨터공학과", "전공필수", "3.0", schedule,
				"2026/2학기", "대학");
	}

}
