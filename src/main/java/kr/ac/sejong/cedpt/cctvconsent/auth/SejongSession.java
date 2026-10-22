package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 세종대 서버들(portal, sjpt)에 대한 HTTP 세션 하나. 쿠키 저장소를 세션마다 새로 만들어 학생 간 세션이 섞이지 않게 한다.
 *
 * 비밀번호는 세종대 서버로 전달만 하고 저장하거나 로그에 남기지 않는다.
 * 로그인 실패 시 재시도하지 않는다 (포털은 5회 이상 실패하면 비밀번호 초기화를 요구함).
 * 요청, 리다이렉트, 쿠키는 https://*.sejong.ac.kr 로만 제한한다.
 */
final class SejongSession implements AutoCloseable {

	private static final URI PORTAL_URI = URI.create("https://portal.sejong.ac.kr");
	private static final URI LOGIN_URI = URI.create("https://portal.sejong.ac.kr/jsp/login/login_action.jsp");
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final int MAX_REDIRECTS = 5;

	// 포털 방화벽이 curl, Java 기본 User-Agent 요청을 차단 페이지로 응답한다
	private static final String USER_AGENT =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36";

	// login_action.jsp 응답의 스크립트: var result = 'OK' | 'erridpwd' | 'pwdNeedChg' | ...
	private static final Pattern LOGIN_RESULT = Pattern.compile("var\\s+result\\s*=\\s*'([^']*)'");

	static {
		// main()에서 먼저 적용하지만, main()을 거치지 않는 테스트를 위해 여기서도 호출한다
		SejongTls.allowLegacyRsaCipher();
	}

	private final HttpClient client;

	SejongSession() {
		CookieManager cookies = new CookieManager(null, (uri, cookie) -> isSejongHttps(uri));
		HttpCookie chknos = new HttpCookie("chknos", "false");
		chknos.setPath("/");
		chknos.setVersion(0);
		cookies.getCookieStore().add(PORTAL_URI, chknos);

		this.client = HttpClient.newBuilder()
				.cookieHandler(cookies)
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(TIMEOUT)
				.build();
	}

	/**
	 * 포털에 로그인한다. 성공하면 *.sejong.ac.kr 공통 SSO 쿠키가 이 세션에 남는다.
	 *
	 * @param returnHost 로그인 뒤 돌아갈 서비스 호스트 (포털 rtUrl)
	 */
	void login(String studentId, String password, String returnHost) throws IOException, InterruptedException {
		String form = "mainLogin=N"
				+ "&rtUrl=" + encode(returnHost)
				+ "&id=" + encode(studentId)
				+ "&password=" + encode(password);
		HttpRequest request = HttpRequest.newBuilder(LOGIN_URI)
				.timeout(TIMEOUT)
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Referer", PORTAL_URI.toString())
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build();

		checkLoginResult(send(request).body());
	}

	HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
		return send(getRequest(uri));
	}

	HttpResponse<String> postJson(URI uri, String json) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(TIMEOUT)
				.header("User-Agent", USER_AGENT)
				.header("Accept", "application/json")
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Referer", uri.getScheme() + "://" + uri.getHost() + "/")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
		return send(request);
	}

	/**
	 * 포털 로그인 응답의 결과 코드를 해석한다. 'OK'가 아니면 예외를 던진다.
	 * 메시지는 포털이 각 코드에 띄우는 alert 문구를 따른다.
	 */
	static void checkLoginResult(String body) {
		Matcher result = LOGIN_RESULT.matcher(body);
		if (!result.find()) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"포털 로그인 응답에서 결과 코드를 찾을 수 없습니다.");
		}
		switch (result.group(1)) {
			case "OK" -> {
			}
			case "erridpwd", "Error" -> throw new SejongAuthException(SejongAuthException.Reason.INVALID_CREDENTIALS,
					"학번 또는 비밀번호가 올바르지 않습니다.");
			case "pwdNeedChg" -> throw new SejongAuthException(SejongAuthException.Reason.ACCOUNT_RESTRICTED,
					"비밀번호를 여러 번 잘못 입력해 포털 계정이 잠겼습니다. 포털에서 비밀번호를 재설정하세요.");
			case "invalidDt", "invalid" -> throw new SejongAuthException(SejongAuthException.Reason.ACCOUNT_RESTRICTED,
					"현재 로그인할 수 없는 포털 계정입니다.");
			default -> throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"알 수 없는 포털 로그인 결과: " + result.group(1));
		}
	}

	/**
	 * 리다이렉트를 직접 따라간다. 301/302/303만 GET으로 따라가고(요청 본문을 다시 보내지 않음),
	 * 대상은 https://*.sejong.ac.kr 이어야 한다.
	 */
	private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
		HttpRequest current = request;
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			HttpResponse<String> response = client.send(current, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status >= 200 && status < 300) {
				return response;
			}
			if (status != 301 && status != 302 && status != 303) {
				throw new SejongAuthException(SejongAuthException.Reason.UNAVAILABLE,
						current.uri().getHost() + current.uri().getPath() + " 응답 오류: HTTP " + status);
			}
			current = getRequest(redirectTarget(current.uri(), response));
		}
		throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE, "리다이렉트가 너무 많습니다.");
	}

	private static URI redirectTarget(URI from, HttpResponse<?> response) {
		String location = response.headers().firstValue("Location")
				.orElseThrow(() -> new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
						"리다이렉트 응답에 Location 헤더가 없습니다."));
		URI target;
		try {
			target = from.resolve(location);
		}
		catch (IllegalArgumentException ex) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE, "잘못된 리다이렉트 주소입니다.", ex);
		}
		if (!isSejongHttps(target)) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"세종대 밖으로의 리다이렉트를 거부했습니다: " + target.getScheme() + "://" + target.getHost());
		}
		return target;
	}

	static boolean isSejongHttps(URI uri) {
		String host = uri.getHost();
		return "https".equalsIgnoreCase(uri.getScheme())
				&& host != null
				&& (host.equalsIgnoreCase("sejong.ac.kr") || host.toLowerCase().endsWith(".sejong.ac.kr"));
	}

	private static HttpRequest getRequest(URI uri) {
		return HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", USER_AGENT).GET().build();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	@Override
	public void close() {
		client.close();
	}

}
