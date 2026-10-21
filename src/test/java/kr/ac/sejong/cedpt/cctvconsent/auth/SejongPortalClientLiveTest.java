package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 세종대 포털에 접속하는 테스트. CI와 일반 빌드에서는 실행되지 않는다.
 *
 * 실행: 프로젝트 루트 .env 에 SEJONG_TEST_ID, SEJONG_TEST_PASSWORD 를 채운 뒤
 *   ./gradlew test --tests '*SejongPortalClientLiveTest' --rerun -i
 * (.env 는 build.gradle 의 test 태스크가 읽어 환경 변수로 넘기며, git 에는 올라가지 않는다)
 */
@EnabledIfEnvironmentVariable(named = "SEJONG_TEST_ID", matches = ".+")
class SejongPortalClientLiveTest {

	private final SejongPortalClient client = new SejongPortalClient();

	@Test
	void authenticatesRealAccount() {
		String studentId = System.getenv("SEJONG_TEST_ID");

		SejongStudent student = client.authenticate(studentId, System.getenv("SEJONG_TEST_PASSWORD"));

		System.out.println("세종대 인증 결과: " + student);
		assertThat(student.studentId()).isEqualTo(studentId);
		assertThat(student.name()).isNotBlank();
	}

	@Test
	void rejectsUnknownAccount() {
		assertThatThrownBy(() -> client.authenticate("00000000", "wrongpass1"))
				.isInstanceOfSatisfying(SejongAuthException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(SejongAuthException.Reason.INVALID_CREDENTIALS));
	}

}
