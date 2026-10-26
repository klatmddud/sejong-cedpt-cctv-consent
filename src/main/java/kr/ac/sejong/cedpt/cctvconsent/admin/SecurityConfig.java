package kr.ac.sejong.cedpt.cctvconsent.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import kr.ac.sejong.cedpt.cctvconsent.auth.LoginAttemptLimiter;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentAuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;
import org.springframework.web.servlet.support.SessionFlashMapManager;

/**
 * 관리자와 학생이 같은 로그인 화면(/login)을 쓴다. 아이디가 설정된 관리자 아이디와 같으면 관리자 계정(공용 계정 하나,
 * BCrypt 해시)으로, 숫자뿐이면 학번으로 보고 세종대 포털로 확인한다. 로그인 뒤 관리자는 /admin, 학생은 /consents로 간다.
 * 영문자가 섞였지만 관리자 아이디가 아닌 것(학번 오타 등)은 관리자 비밀번호가 틀렸을 때와 같은 문구로 거절하고 관리자 로그인
 * 제한(같은 IP 15분에 5번)에도 똑같이 센다. 응답이나 잠금 여부로 관리자 아이디를 추측하지 못하게 하고, BCrypt 확인을
 * 무제한으로 돌리지 못하게 하기 위해서다. 그래서 같은 IP에서 영문 아이디 오타가 쌓이면 관리자 로그인도 잠시 막힌다.
 * /admin/**는 관리자, /consents/**는 학생만 쓸 수 있고, 명시한 경로 외에는 모두 막는다.
 *
 * CSP를 붙인다. 스크립트는 우리 서버 파일만 허용한다(인라인 스크립트 없음). 스타일은 시간표 위치처럼
 * th:style로 넣는 값이 있어 인라인을 허용하고, 학생 화면 글꼴(Pretendard)을 jsdelivr에서 받는다.
 * object-src를 따로 막지 않는 것은 브라우저가 동의서 PDF를 보여 주는 데 쓰기 때문이다(default-src 'self'로 제한됨).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	private static final String CSP = "default-src 'self'; script-src 'self'; "
			+ "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; font-src 'self' https://cdn.jsdelivr.net; "
			+ "img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
	private static final Logger audit = LoggerFactory.getLogger("audit");
	private static final Pattern LETTER = Pattern.compile("[A-Za-z]");

	private static final LoginAttemptLimiter.Policy ADMIN_PER_IP = new LoginAttemptLimiter.Policy(5, Duration.ofMinutes(15));
	// BCrypt(cost 12) 한 번이 작은 서버에서 1초 안팎 걸린다(cost 14는 3~6초). 로그인 없이 보낼 수 있는 요청이라 동시에 몇 개까지만 돌린다
	private static final int MAX_PASSWORD_CHECKS = 2;
	private static final Duration PASSWORD_CHECK_WAIT = Duration.ofSeconds(2);

	@Bean
	SecurityFilterChain security(HttpSecurity http, StudentAuthenticationProvider students,
			UserDetailsService adminAccount, PasswordEncoder encoder, LoginAttemptLimiter limiter,
			@Value("${admin.username}") String adminUsername) throws Exception {
		String adminHash = adminAccount.loadUserByUsername(adminUsername).getPassword();
		AuthenticationProvider admins = rateLimited(adminAccount, encoder, limiter, adminUsername,
				password -> encoder.matches(password, adminHash));
		http.headers(SecurityConfig::securityHeaders)
				.authenticationManager(new ProviderManager(byUsername(students, admins)))
				// CsrfFilter가 getParameter()로 본문을 파싱하기 전에, 관리자가 아닌 multipart 업로드는 거절한다.
				// 안 그러면 로그인하지 않은 사람도 /admin/roster에 20MB를 보내 본문 파싱을 유발할 수 있다.
				.addFilterBefore(new RejectUnauthenticatedAdminUpload(), CsrfFilter.class)
				// 저장된 요청(saved request)을 쓰지 않는다. RoleTargetSuccessHandler가 역할별 고정 주소로만 보내므로
				// 이 캐시는 안 쓰이는데, 켜 두면 익명 GET마다 요청 헤더까지 세션에 복사해 세션·힙을 늘리는 DoS 통로가 된다.
				.requestCache(cache -> cache.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/", "/login", "/css/**", "/js/**", "/img/**", "/actuator/health", "/error").permitAll()
						.requestMatchers("/admin/**").hasRole("ADMIN")
						.requestMatchers("/consents/**").hasRole("STUDENT")
						.anyRequest().denyAll())
				.formLogin(form -> form
						.loginPage("/login")
						.successHandler(new RoleTargetSuccessHandler())
						.failureUrl("/login?error"))
				.logout(logout -> logout
						.logoutUrl("/logout")
						.logoutSuccessHandler(new LoggedOutNoticeHandler()));
		return http.build();
	}

	/**
	 * 관리자는 /admin, 학생은 /consents로 보낸다. 요청했던 주소로 돌려보내지 않는다(외부 주소로 보내는 것 방지).
	 * SimpleUrlAuthenticationSuccessHandler를 써야 세션에 남은 이전 로그인 실패 사유도 지워진다.
	 */
	private static final class RoleTargetSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

		@Override
		protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
				Authentication authentication) {
			return isAdmin(authentication) ? "/admin" : "/consents";
		}

	}

	/**
	 * 로그아웃 뒤 /login으로 보내고, 안내("로그아웃했습니다")는 flash 속성(loggedOut)으로 넘겨 한 번만 보여 준다.
	 * 주소(/login?logout)로 넘기면 새로고침할 때마다 안내가 다시 뜬다. 세션은 이미 무효화되어 flash용 새 세션이 생긴다.
	 */
	private static final class LoggedOutNoticeHandler extends SimpleUrlLogoutSuccessHandler {

		private final FlashMapManager flashMaps = new SessionFlashMapManager();

		LoggedOutNoticeHandler() {
			setDefaultTargetUrl("/login");
			setAlwaysUseDefaultTargetUrl(true);
		}

		@Override
		public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
				Authentication authentication) throws IOException, ServletException {
			FlashMap flash = new FlashMap();
			flash.put("loggedOut", true);
			flash.setTargetRequestPath(request.getContextPath() + "/login");
			flashMaps.saveOutputFlashMap(flash, request, response);
			super.onLogoutSuccess(request, response, authentication);
		}

	}

	/**
	 * 관리자가 아닌 사용자의 multipart 요청(/admin/**)을 본문을 읽기 전에 거절한다. CsrfFilter 앞에 두어,
	 * getParameter()가 큰 본문(최대 20MB)을 파싱하기 전에 막는다. content-type 헤더만 보고 본문은 읽지 않는다.
	 * 경로는 authorizeHttpRequests와 같은 매처로 본다. getRequestURI()는 디코딩 전이라 /%61dmin/roster로 우회된다.
	 */
	private static final class RejectUnauthenticatedAdminUpload extends OncePerRequestFilter {

		// 메서드는 가리지 않는다(authorizeHttpRequests의 /admin/**와 범위를 맞춘다)
		private static final RequestMatcher ADMIN = PathPatternRequestMatcher.withDefaults().matcher("/admin/**");

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			String contentType = request.getContentType();
			if (contentType != null && contentType.regionMatches(true, 0, "multipart/", 0, "multipart/".length())
					&& ADMIN.matches(request)
					&& !isAdmin(SecurityContextHolder.getContext().getAuthentication())) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
				return;
			}
			chain.doFilter(request, response);
		}

	}

	/** 영문자가 섞인 아이디는 관리자 로그인, 그 밖(학번)은 학생 로그인으로 보낸다. 앞뒤 공백은 뗀다. */
	private static AuthenticationProvider byUsername(AuthenticationProvider students, AuthenticationProvider admins) {
		return new AuthenticationProvider() {

			@Override
			public Authentication authenticate(Authentication authentication) {
				String username = authentication.getName() == null ? "" : authentication.getName().trim();
				UsernamePasswordAuthenticationToken trimmed = UsernamePasswordAuthenticationToken.unauthenticated(
						username, authentication.getCredentials());
				trimmed.setDetails(authentication.getDetails());
				if (LETTER.matcher(username).find()) {
					return admins.authenticate(trimmed);
				}
				return students.authenticate(trimmed);
			}

			@Override
			public boolean supports(Class<?> authentication) {
				return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
			}

		};
	}

	private static boolean isAdmin(Authentication authentication) {
		return authentication != null
				&& authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
	}

	private static void securityHeaders(HeadersConfigurer<HttpSecurity> headers) {
		headers.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN));
	}

	@Bean
	UserDetailsService adminAccount(@Value("${admin.username}") String username,
			@Value("${admin.password-hash}") String passwordHash) {
		// 숫자뿐인 아이디는 학번과 겹칠 수 있어 받지 않는다(같은 로그인 화면에서 학생이 그 학번으로 로그인할 수 없게 된다)
		if (!LETTER.matcher(username).find() || !username.equals(username.trim())) {
			throw new IllegalStateException(
					"관리자 아이디(ADMIN_USERNAME)에는 영문자가 있어야 하고 앞뒤에 공백이 없어야 합니다: " + username);
		}
		if (!passwordHash.startsWith("$2")) {
			throw new IllegalStateException(
					"관리자 비밀번호 해시(ADMIN_PASSWORD_HASH, BCrypt)가 설정되지 않았습니다. deploy/README.md를 참고하세요.");
		}
		return new InMemoryUserDetailsManager(User.withUsername(username)
				.password(passwordHash)
				.roles("ADMIN")
				.build());
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/**
	 * 같은 IP에서 관리자 로그인(영문자가 섞인 아이디)을 15분에 5번 넘게 틀리면 막는다.
	 * 관리자 아이디가 아닌 영문 아이디도 같은 횟수로 세고 같은 메시지를 보여, 잠금 여부나 응답으로 관리자 아이디를
	 * 알아내지 못하게 하고 BCrypt 확인을 무제한으로 돌리지 못하게 한다.
	 *
	 * @param checkAdminPassword 관리자 아이디가 아닐 때도 같은 시간이 걸리도록 비밀번호 확인(BCrypt)을 한 번 한다.
	 *        결과는 쓰지 않는다.
	 */
	private static AuthenticationProvider rateLimited(UserDetailsService accounts, PasswordEncoder encoder,
			LoginAttemptLimiter limiter, String adminUsername, Predicate<String> checkAdminPassword) {
		DaoAuthenticationProvider admin = new DaoAuthenticationProvider(accounts);
		admin.setPasswordEncoder(encoder);
		AuthenticationProvider delegate = new AuthenticationProvider() {

			@Override
			public Authentication authenticate(Authentication authentication) {
				if (adminUsername.equals(authentication.getName())) {
					return admin.authenticate(authentication);
				}
				checkAdminPassword.test(String.valueOf(authentication.getCredentials()));
				throw new BadCredentialsException("login.error.adminInvalid");
			}

			@Override
			public boolean supports(Class<?> authentication) {
				return admin.supports(authentication);
			}

		};
		Semaphore passwordChecks = new Semaphore(MAX_PASSWORD_CHECKS);
		return new AuthenticationProvider() {

			@Override
			public Authentication authenticate(Authentication authentication) {
				String key = "admin-ip:" + (authentication.getDetails() instanceof WebAuthenticationDetails details
						? details.getRemoteAddress() : "unknown");
				// 실패를 미리 하나 기록해 두고 확인한다. 동시에 보낸 요청이 모두 한도 확인을 통과하지 못하게 한다
				if (!limiter.tryReserve(key, ADMIN_PER_IP)) {
					audit.warn("관리자 로그인 차단 [{}]", key.substring("admin-ip:".length()));
					throw new LockedException("login.error.adminLocked");
				}
				if (!acquire(passwordChecks)) {
					limiter.cancelLast(key);
					throw new AuthenticationServiceException("login.error.busy");
				}
				try {
					Authentication result = delegate.authenticate(authentication);
					// 이번 시도만 되돌린다. reset하면 같은 IP(공유기)를 쓰는 다른 사람의 실패까지 지워져 한도가 다시 찬다
					limiter.cancelLast(key);
					audit.info("관리자 로그인 성공 [{}]", key.substring("admin-ip:".length()));
					return result;
				}
				catch (BadCredentialsException ex) {
					audit.warn("관리자 로그인 실패 [{}]", key.substring("admin-ip:".length()));
					throw new BadCredentialsException("login.error.adminInvalid", ex);
				}
				catch (AuthenticationException ex) {
					// 해시 형식 오류 같은 설정 문제는 비밀번호 실패로 세지 않고 그대로 드러낸다
					limiter.cancelLast(key);
					audit.error("관리자 로그인 오류 [{}]: {}", key.substring("admin-ip:".length()), ex.getClass().getName());
					throw ex;
				}
				finally {
					passwordChecks.release();
				}
			}

			private static boolean acquire(Semaphore semaphore) {
				try {
					return semaphore.tryAcquire(PASSWORD_CHECK_WAIT.toMillis(), TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return false;
				}
			}

			@Override
			public boolean supports(Class<?> authentication) {
				return delegate.supports(authentication);
			}

		};
	}

}
