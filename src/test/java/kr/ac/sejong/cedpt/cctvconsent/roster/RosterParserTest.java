package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterWorkbookFixture.FixtureCourse;
import org.junit.jupiter.api.Test;

class RosterParserTest {

	private static final String TITLE = "2026년도 2학기 출석부";

	@Test
	void parsesPagedAndDuplicatedBlocks() {
		byte[] xlsx = RosterWorkbookFixture.workbook(TITLE, 3, List.of(
				FixtureCourse.of("000304-001", "센B201", "20010001", "20010002", "20010003", "20010004", "20010005"),
				FixtureCourse.of("000305-001", "호302/센B205", "20010001", "20010006"),
				FixtureCourse.of("000306-001", null, "20010007")));

		RosterFile roster = RosterParser.parse(new ByteArrayInputStream(xlsx));

		assertThat(roster.year()).isEqualTo(2026);
		assertThat(roster.term()).isEqualTo("2");
		assertThat(roster.courses()).extracting(RosterFile.Course::code)
				.containsExactly("000304-001", "000305-001", "000306-001");

		RosterFile.Course paged = roster.courses().getFirst();
		assertThat(paged.name()).isEqualTo("과목000304-001");
		assertThat(paged.professor()).isEqualTo("교수000304-001");
		assertThat(paged.schedule()).isEqualTo("월10:30-12:00");
		assertThat(paged.room()).isEqualTo("센B201");
		assertThat(paged.students()).extracting(RosterFile.Student::studentId)
				.containsExactly("20010001", "20010002", "20010003", "20010004", "20010005");
		assertThat(paged.students().getFirst())
				.isEqualTo(new RosterFile.Student("20010001", "학생01", "컴퓨터공학과", "2"));

		assertThat(roster.courses().get(1).room()).isEqualTo("호302/센B205");
		RosterFile.Course noRoom = roster.courses().get(2);
		assertThat(noRoom.room()).isNull();
		assertThat(noRoom.schedule()).isNull();
	}

	@Test
	void rejectsCourseWhoseStudentCountDiffersFromDeclared() {
		byte[] xlsx = RosterWorkbookFixture.workbook(TITLE, 45, List.of(
				FixtureCourse.of("000304-001", "센B201", "20010001"),
				new FixtureCourse("000305-001", "과목", "센B202", 3, List.of("20010001", "20010002"))));

		assertThatThrownBy(() -> RosterParser.parse(new ByteArrayInputStream(xlsx)))
				.isInstanceOf(RosterParseException.class)
				.hasMessageContaining("000305-001(수강인원 3명, 명단 2명)")
				.hasMessageNotContaining("000304-001");
	}

	@Test
	void readsNumericStudentIdCells() {
		byte[] xlsx = RosterWorkbookFixture.workbook(TITLE, new RosterWorkbookFixture.Options(45, true, null),
				List.of(FixtureCourse.of("000304-001", "센B201", "20010001", "20010002")));

		RosterFile roster = RosterParser.parse(new ByteArrayInputStream(xlsx));

		assertThat(roster.courses().getFirst().students()).extracting(RosterFile.Student::studentId)
				.containsExactly("20010001", "20010002");
	}

	@Test
	void acceptsTitleWithNonBreakingSpaces() {
		byte[] xlsx = RosterWorkbookFixture.workbook("2026년도 2학기  출석부", 45,
				List.of(FixtureCourse.of("000304-001", "센B201", "20010001")));

		RosterFile roster = RosterParser.parse(new ByteArrayInputStream(xlsx));

		assertThat(roster.year()).isEqualTo(2026);
		assertThat(roster.term()).isEqualTo("2");
	}

	@Test
	void rejectsStudentWithoutName() {
		byte[] xlsx = RosterWorkbookFixture.workbook(TITLE, new RosterWorkbookFixture.Options(45, false, "20010002"),
				List.of(FixtureCourse.of("000304-001", "센B201", "20010001", "20010002")));

		assertThatThrownBy(() -> RosterParser.parse(new ByteArrayInputStream(xlsx)))
				.isInstanceOf(RosterParseException.class)
				.hasMessageContaining("000304-001")
				.hasMessageContaining("20010002");
	}

	@Test
	void rejectsWorkbookThatIsNotARoster() {
		byte[] xlsx = RosterWorkbookFixture.workbook("일반 문서", 45, List.of());

		assertThatThrownBy(() -> RosterParser.parse(new ByteArrayInputStream(xlsx)))
				.isInstanceOf(RosterParseException.class)
				.hasMessageContaining("출석부");
	}

	@Test
	void rejectsNonXlsxBytes() {
		assertThatThrownBy(() -> RosterParser.parse(new ByteArrayInputStream("not an xlsx".getBytes())))
				.isInstanceOf(RosterParseException.class)
				.hasMessageContaining("xlsx");
	}

}
