package kr.ac.sejong.cedpt.cctvconsent.local;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * local 프로필 전용 PostgreSQL. Docker나 설치 없이 실제 PostgreSQL 17을 띄운다.
 * embedded-postgres는 developmentOnly 의존성이라 배포 jar에는 없고, 이 클래스도 local 프로필에서만 로드된다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalDatabaseConfig {

	private static final int PORT = 54329;

	@Bean(destroyMethod = "close")
	EmbeddedPostgres localPostgres() throws IOException {
		return EmbeddedPostgres.builder()
				.setPort(PORT)
				.setDataDirectory(new File("build/local-postgres"))
				.setCleanDataDirectory(false)
				.start();
	}

	@Bean
	DataSource dataSource(EmbeddedPostgres localPostgres) {
		return localPostgres.getPostgresDatabase();
	}

}
