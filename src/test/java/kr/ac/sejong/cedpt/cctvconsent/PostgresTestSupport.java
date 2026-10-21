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

	protected static final String ADMIN_USERNAME = "admin";
	protected static final String ADMIN_PASSWORD = "test-admin";
	// htpasswd -nbBC 4 "" test-admin
	private static final String ADMIN_PASSWORD_HASH = "$2y$04$Gi6THaCYUt0tzbUFtadLre7kVJIFAjOQ7uZzWzUvFZoT3KwlkLrau";

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
		registry.add("admin.username", () -> ADMIN_USERNAME);
		registry.add("admin.password-hash", () -> ADMIN_PASSWORD_HASH);
	}

}
