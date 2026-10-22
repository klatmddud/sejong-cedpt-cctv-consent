package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 세종대 포털, sjpt에 접속하는 테스트. CI와 일반 빌드에서는 실행되지 않는다.
 *
 * 실행: 프로젝트 루트 .env 에 SEJONG_TEST_ID, SEJONG_TEST_PASSWORD 를 채운 뒤
 *   ./gradlew test --tests '*SjptClientLiveTest' --rerun -i
 * (.env 는 build.gradle 의 test 태스크가 읽어 환경 변수로 넘기며, git 에는 올라가지 않는다)
 * 다른 학기를 보려면 SEJONG_TEST_TERM=2026-1 처럼 지정한다 (없으면 현재 학기).
 */
@EnabledIfEnvironmentVariable(named = "SEJONG_TEST_ID", matches = ".+")
class SjptClientLiveTest {

	private final SjptClient client = new SjptClient();

	@Test
	void authenticatesRealAccount() {
		String studentId = System.getenv("SEJONG_TEST_ID");

		StudentLogin login = client.authenticate(studentId, System.getenv("SEJONG_TEST_PASSWORD"));

		assertThat(login.student().studentId()).isEqualTo(studentId);
		assertThat(login.student().name()).isNotBlank();
		assertThat(login.coursesLoaded()).isTrue();
		login.courses().forEach(course -> System.out.println("로그인 수강 과목: " + course.code() + " " + course.times()
				+ " | " + course.rooms()));
	}

	@Test
	void rejectsUnknownAccount() {
		assertThatThrownBy(() -> client.authenticate("00000000", "wrongpass1"))
				.isInstanceOfSatisfying(SejongAuthException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(SejongAuthException.Reason.INVALID_CREDENTIALS));
	}

	@Test
	void listsCoursesOfRealAccount() {
		String studentId = System.getenv("SEJONG_TEST_ID");
		String password = System.getenv("SEJONG_TEST_PASSWORD");
		String term = System.getenv("SEJONG_TEST_TERM");

		List<SjptCourse> courses = term == null || term.isBlank()
				? client.courses(studentId, password)
				: client.courses(studentId, password, new AcademicTerm(
						Integer.parseInt(term.split("-")[0]), Integer.parseInt(term.split("-")[1])));

		courses.forEach(course -> System.out.println("sjpt 수강 과목: " + course.code() + " " + course.name()));
		assertThat(courses).allSatisfy(course -> {
			assertThat(course.code()).isNotBlank();
			assertThat(course.name()).isNotBlank();
		});
	}

}
