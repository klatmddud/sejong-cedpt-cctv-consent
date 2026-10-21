package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Security;
import org.junit.jupiter.api.Test;

class SejongTlsTest {

	@Test
	void removesOnlyLegacyRsaPattern() {
		SejongTls.allowLegacyRsaCipher();

		String disabled = Security.getProperty("jdk.tls.disabledAlgorithms");
		assertThat(disabled).doesNotContain("TLS_RSA_*").contains("SSLv3", "RC4", "3DES_EDE_CBC");
	}

}
