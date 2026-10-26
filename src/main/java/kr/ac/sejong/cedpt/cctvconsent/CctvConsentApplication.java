package kr.ac.sejong.cedpt.cctvconsent;

import java.util.Map;
import kr.ac.sejong.cedpt.cctvconsent.auth.SejongTls;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.util.ClassUtils;

@SpringBootApplication
public class CctvConsentApplication {

	/**
	 * local 프로필의 내장 DB(LocalDatabaseConfig)가 쓰는 라이브러리. developmentOnly 의존성이라 소스에서 실행할 때만
	 * 클래스패스에 있고 배포 jar에는 없다. 그래서 local 패키지가 실수로 jar에 들어가도 운영에서 local이 켜지지 않는다.
	 */
	private static final String EMBEDDED_POSTGRES = "io.zonky.test.db.postgres.embedded.EmbeddedPostgres";

	public static void main(String[] args) {
		// 다른 TLS 연결(DB 등)이 JSSE 설정을 먼저 읽기 전에 적용해야 한다
		SejongTls.allowLegacyRsaCipher();
		SpringApplication app = new SpringApplication(CctvConsentApplication.class);
		// 소스에서 실행하면(IDE, ./gradlew bootRun) local을 기본 프로필로 둔다.
		// 기본 프로필이라 spring.profiles.active로 다른 프로필을 지정하면 local은 켜지지 않는다
		if (ClassUtils.isPresent(EMBEDDED_POSTGRES, CctvConsentApplication.class.getClassLoader())) {
			app.setDefaultProperties(Map.of("spring.profiles.default", "local"));
		}
		app.run(args);
	}

}
