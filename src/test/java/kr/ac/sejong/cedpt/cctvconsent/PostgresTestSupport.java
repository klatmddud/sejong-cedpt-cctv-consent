package kr.ac.sejong.cedpt.cctvconsent;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spring 컨텍스트 테스트용 PostgreSQL. Docker 없이 실제 PostgreSQL 바이너리를 띄워 JVM 하나에서 공유한다.
 */
public abstract class PostgresTestSupport {

	private static final EmbeddedPostgres POSTGRES = start();

	private static EmbeddedPostgres start() {
		try {
			EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					postgres.close();
				}
				catch (IOException ignored) {
				}
			}));
			return postgres;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.datasource.password", () -> "postgres");
	}

}
