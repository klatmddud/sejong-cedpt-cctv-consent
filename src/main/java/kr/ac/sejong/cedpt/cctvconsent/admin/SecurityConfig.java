package kr.ac.sejong.cedpt.cctvconsent.admin;

import java.time.Duration;
import kr.ac.sejong.cedpt.cctvconsent.auth.LoginAttemptLimiter;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentAuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * 관리자(/admin/**)와 학생(그 밖의 경로)은 로그인 방식이 달라 보안 설정을 둘로 나눈다.
 * 관리자: 공용 계정 하나(BCrypt 해시). 학생: 세종대 포털 인증. 명시한 경로 외에는 모두 막는다.
 *
 * 두 설정 모두 CSP를 붙인다. 스크립트는 우리 서버 파일만 허용한다(인라인 스크립트 없음). 스타일은 시간표 위치처럼
 * th:style로 넣는 값이 있어 인라인을 허용하고, 학생 화면 글꼴(Pretendard)을 jsdelivr에서 받는다.
 * object-src를 따로 막지 않는 것은 브라우저가 동의서 PDF를 보여 주는 데 쓰기 때문이다(default-src 'self'로 제한됨).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	private static final String CSP = "default-src 'self'; script-src 'self'; "
			+ "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; font-src 'self' https://cdn.jsdelivr.net; "
			+ "img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
	private static final Logger audit = LoggerFactory.getLogger("audit");

	private static final LoginAttemptLimiter.Policy ADMIN_PER_IP = new LoginAttemptLimiter.Policy(5, Duration.ofMinutes(15));

	@Bean
	@Order(1)
	SecurityFilterChain adminSecurity(HttpSecurity http, UserDetailsService adminAccount, PasswordEncoder encoder,
			LoginAttemptLimiter limiter) throws Exception {
		http.securityMatcher("/admin/**")
				.headers(SecurityConfig::securityHeaders)
				.authenticationManager(new ProviderManager(rateLimited(adminAccount, encoder, limiter)))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/admin/login").permitAll()
						.anyRequest().hasRole("ADMIN"))
				.formLogin(form -> form
						.loginPage("/admin/login")
						.defaultSuccessUrl("/admin", true)
						.failureUrl("/admin/login?error"))
				.logout(logout -> logout
						.logoutUrl("/admin/logout")
						.logoutSuccessUrl("/admin/login?logout"));
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain studentSecurity(HttpSecurity http, StudentAuthenticationProvider students) throws Exception {
		http.headers(SecurityConfig::securityHeaders)
				.authenticationManager(new ProviderManager(students))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/", "/login", "/css/**", "/js/**", "/actuator/health", "/error").permitAll()
						.requestMatchers("/consents/**").hasRole("STUDENT")
						.anyRequest().denyAll())
				.formLogin(form -> form
						.loginPage("/login")
						.defaultSuccessUrl("/consents", true)
						.failureUrl("/login?error"))
				.logout(logout -> logout
						.logoutUrl("/logout")
						.logoutSuccessUrl("/login?logout"));
		return http.build();
	}

	private static void securityHeaders(HeadersConfigurer<HttpSecurity> headers) {
		headers.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN));
	}

	@Bean
	UserDetailsService adminAccount(@Value("${admin.username}") String username,
			@Value("${admin.password-hash}") String passwordHash) {
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

	/** 같은 IP에서 관리자 로그인을 15분에 5번 넘게 틀리면 막는다. */
	private static AuthenticationProvider rateLimited(UserDetailsService accounts, PasswordEncoder encoder,
			LoginAttemptLimiter limiter) {
		DaoAuthenticationProvider delegate = new DaoAuthenticationProvider(accounts);
		delegate.setPasswordEncoder(encoder);
		return new AuthenticationProvider() {

			@Override
			public Authentication authenticate(Authentication authentication) {
				String key = "admin-ip:" + (authentication.getDetails() instanceof WebAuthenticationDetails details
						? details.getRemoteAddress() : "unknown");
				if (limiter.isBlocked(key, ADMIN_PER_IP)) {
					audit.warn("관리자 로그인 차단 [{}]", key.substring("admin-ip:".length()));
					throw new LockedException("로그인에 여러 번 실패했습니다. 15분 뒤 다시 시도하세요.");
				}
				try {
					Authentication result = delegate.authenticate(authentication);
					limiter.reset(key);
					audit.info("관리자 로그인 성공 [{}]", key.substring("admin-ip:".length()));
					return result;
				}
				catch (AuthenticationException ex) {
					limiter.recordFailure(key, ADMIN_PER_IP);
					audit.warn("관리자 로그인 실패 [{}]", key.substring("admin-ip:".length()));
					throw ex;
				}
			}

			@Override
			public boolean supports(Class<?> authentication) {
				return delegate.supports(authentication);
			}

		};
	}

}
