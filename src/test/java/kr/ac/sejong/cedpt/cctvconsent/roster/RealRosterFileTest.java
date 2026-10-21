package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 수강명부로 선별 결과를 확인한다. 명부는 개인정보라 저장소에 두지 않으며, 집계 수치만 출력한다.
 *
 * 실행: ROSTER_XLSX=~/workspace/컴퓨터공학과_전체수강명부.xlsx ./gradlew test --tests '*RealRosterFileTest' --rerun -i
 * (기대값은 2026-2학기 명부 기준이다. 다른 학기 명부로는 수치 검증이 실패한다.)
 */
@EnabledIfEnvironmentVariable(named = "ROSTER_XLSX", matches = ".+")
class RealRosterFileTest {

	@Test
	void selectsTargetRoomCoursesFrom2026FallRoster() throws IOException {
		RosterFile roster;
		try (InputStream in = Files.newInputStream(Path.of(System.getenv("ROSTER_XLSX")))) {
			roster = RosterParser.parse(in);
		}
		TargetRooms targetRooms = new TargetRooms("B201,B202,B203,B206,B207");
		List<RosterFile.Course> targets = roster.courses().stream()
				.filter(course -> targetRooms.includes(course.room()))
				.toList();
		int enrollments = targets.stream().mapToInt(course -> course.students().size()).sum();
		Set<String> students = targets.stream()
				.flatMap(course -> course.students().stream())
				.map(RosterFile.Student::studentId)
				.collect(Collectors.toSet());

		System.out.printf("명부 %d-%s학기: 전체 %d과목, 대상 %d과목, 동의서 %d건, 학생 %d명%n",
				roster.year(), roster.term(), roster.courses().size(), targets.size(), enrollments, students.size());
		assertThat(roster.courses()).hasSize(93);
		assertThat(targets).hasSize(56);
		assertThat(enrollments).isEqualTo(1789);
		assertThat(students).hasSize(1156);
		assertThat(roster.courses()).extracting(RosterFile.Course::name)
				.noneMatch(name -> name.endsWith("(GRADE)") || name.endsWith("(P/NP)"));
	}

}
