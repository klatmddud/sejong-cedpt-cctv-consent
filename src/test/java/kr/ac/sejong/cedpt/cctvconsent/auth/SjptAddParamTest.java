package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SjptAddParamTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '`', value = {
			"{\"a\":\"x y#\"}            | %7B%22a%22:%22x%20y#%22%7D",
			"`;,/?:@&=+$-_.!~*'()#`     | `;,/?:@&=+$-_.!~*'()#`",
			"AZaz09                     | AZaz09",
			"2학기                       | 2%ED%95%99%EA%B8%B0",
			"`[]<>%\\^|`                | %5B%5D%3C%3E%25%5C%5E%7C",
			"😀                         | %F0%9F%98%80"
	})
	void encodesLikeJavaScriptEncodeUri(String input, String expected) {
		assertThat(SjptAddParam.encodeUri(input)).isEqualTo(expected);
	}

	@Test
	void rejectsLoneSurrogate() {
		assertThatThrownBy(() -> SjptAddParam.encodeUri("a\uD800b")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void encodesBase64OfEncodedUriForQueryString() {
		String json = "{\"_runPgmKey\":\"X\",\"_runSysKey\":\"SCH\",\"name\":\"수강 내역\"}";

		String param = SjptAddParam.encode(json);

		assertThat(param).doesNotContain("+", "/", "=");
		String base64 = URLDecoder.decode(param, StandardCharsets.US_ASCII);
		String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.US_ASCII);
		assertThat(decoded).isEqualTo(SjptAddParam.encodeUri(json));
		assertThat(URLDecoder.decode(decoded.replace("+", "%2B"), StandardCharsets.UTF_8)).isEqualTo(json);
	}

}
