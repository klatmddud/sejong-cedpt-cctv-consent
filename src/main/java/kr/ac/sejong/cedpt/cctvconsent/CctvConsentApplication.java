package kr.ac.sejong.cedpt.cctvconsent;

import kr.ac.sejong.cedpt.cctvconsent.auth.SejongTls;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CctvConsentApplication {

	public static void main(String[] args) {
		// 다른 TLS 연결(DB 등)이 JSSE 설정을 먼저 읽기 전에 적용해야 한다
		SejongTls.allowLegacyRsaCipher();
		SpringApplication.run(CctvConsentApplication.class, args);
	}

}
