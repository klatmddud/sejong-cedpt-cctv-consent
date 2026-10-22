package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * sjpt API의 addParam 쿼리 파라미터. 사이트 스크립트(com.Submit.js)의 btoa(encodeURI(JSON.stringify(obj)))와 같게 만든다.
 */
final class SjptAddParam {

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	// JS encodeURI가 그대로 두는 문자 (영숫자 제외). URLEncoder와 달리 :/?#&= 등을 인코딩하지 않는다.
	private static final String URI_UNESCAPED = ";,/?:@&=+$-_.!~*'()#";

	private SjptAddParam() {
	}

	/**
	 * @param json JSON.stringify 결과에 해당하는 문자열
	 * @return 쿼리 문자열에 바로 붙일 수 있도록 URL 인코딩까지 한 값
	 */
	static String encode(String json) {
		String base64 = Base64.getEncoder().encodeToString(encodeUri(json).getBytes(StandardCharsets.US_ASCII));
		return URLEncoder.encode(base64, StandardCharsets.US_ASCII);
	}

	/**
	 * JS encodeURI와 같은 규칙: 영숫자와 {@value #URI_UNESCAPED}는 그대로 두고, 나머지는 UTF-8 바이트별로 %XX(대문자)로 바꾼다.
	 *
	 * @throws IllegalArgumentException 짝이 맞지 않는 서로게이트 문자 (JS에서는 URIError)
	 */
	static String encodeUri(String value) {
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); ) {
			int codePoint = value.codePointAt(i);
			if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
				throw new IllegalArgumentException("짝이 맞지 않는 서로게이트 문자가 있습니다.");
			}
			if (isUnescaped(codePoint)) {
				out.append((char) codePoint);
			}
			else {
				for (byte b : Character.toString(codePoint).getBytes(StandardCharsets.UTF_8)) {
					out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
				}
			}
			i += Character.charCount(codePoint);
		}
		return out.toString();
	}

	private static boolean isUnescaped(int c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
				|| (c < 128 && URI_UNESCAPED.indexOf(c) >= 0);
	}

}
