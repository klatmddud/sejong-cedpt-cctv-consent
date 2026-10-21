package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.security.Security;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 세종대 포털 서버(portal, classic)는 TLS 1.2 + TLS_RSA_WITH_AES_256_CBC_SHA만 지원한다.
 * JDK 24+는 jdk.tls.disabledAlgorithms 기본값에 "TLS_RSA_*"를 포함하므로 handshake_failure가 난다.
 *
 * 이 설정은 JVM 전체에 적용되며, JSSE가 속성을 처음 읽기 전(첫 TLS 연결 전)에 호출되어야 한다.
 * 인증서 검증과 호스트 이름 검증은 그대로 유지된다.
 */
public final class SejongTls {

	private static final String DISABLED_ALGORITHMS = "jdk.tls.disabledAlgorithms";
	private static final String LEGACY_RSA_PATTERN = "TLS_RSA_*";

	private SejongTls() {
	}

	public static synchronized void allowLegacyRsaCipher() {
		String disabled = Security.getProperty(DISABLED_ALGORITHMS);
		if (disabled == null) {
			return;
		}
		String updated = Arrays.stream(disabled.split(","))
				.map(String::trim)
				.filter(entry -> !entry.equalsIgnoreCase(LEGACY_RSA_PATTERN))
				.collect(Collectors.joining(", "));
		Security.setProperty(DISABLED_ALGORITHMS, updated);
	}

}
