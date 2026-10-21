package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SejongPortalClientTest {

	@ParameterizedTest
	@CsvSource({
			"erridpwd, INVALID_CREDENTIALS",
			"Error, INVALID_CREDENTIALS",
			"pwdNeedChg, ACCOUNT_RESTRICTED",
			"invalidDt, ACCOUNT_RESTRICTED",
			"invalid, ACCOUNT_RESTRICTED",
			"somethingNew, UNEXPECTED_RESPONSE"
	})
	void mapsLoginResultCodes(String code, SejongAuthException.Reason reason) {
		String body = "<script>\n    var result = '" + code + "';\n    if ( result == 'OK' ) {}</script>";

		assertThatThrownBy(() -> SejongPortalClient.checkLoginResult(body))
				.isInstanceOfSatisfying(SejongAuthException.class, ex -> assertThat(ex.getReason()).isEqualTo(reason));
	}

	@ParameterizedTest
	@ValueSource(strings = {"var result = 'OK';", "var result='OK';"})
	void acceptsOkLoginResult(String body) {
		assertThatCode(() -> SejongPortalClient.checkLoginResult(body)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {"<title>Alert!!!</title> 접속을 차단 합니다", ""})
	void rejectsLoginResponseWithoutResultCode(String body) {
		assertThatThrownBy(() -> SejongPortalClient.checkLoginResult(body))
				.isInstanceOfSatisfying(SejongAuthException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(SejongAuthException.Reason.UNEXPECTED_RESPONSE));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://classic.sejong.ac.kr/classic/index.do",
			"https://portal.sejong.ac.kr/jsp/login/login.jsp",
			"https://sejong.ac.kr/"
	})
	void allowsSejongHttpsTargets(String uri) {
		assertThat(SejongPortalClient.isSejongHttps(URI.create(uri))).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://classic.sejong.ac.kr/classic/index.do",
			"https://evil.example.com/classic/reading/status.do",
			"https://sejong.ac.kr.evil.example.com/",
			"https://notsejong.ac.kr/"
	})
	void rejectsOtherTargets(String uri) {
		assertThat(SejongPortalClient.isSejongHttps(URI.create(uri))).isFalse();
	}

}
