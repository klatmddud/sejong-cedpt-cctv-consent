package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * 학생 로그인. 세종대 포털로 학번/비밀번호를 확인한다.
 *
 * 세종대 포털은 5회 실패하면 비밀번호를 초기화해야 하므로, 우리 서버를 통한 실패는 학번당 30분에 3회로 막는다.
 * 같은 IP에서 여러 학번을 시도하는 것도 30분에 10회로 막는다.
 * 예외 메시지는 messages.properties의 코드이며 로그인 화면이 학생이 고른 언어로 바꿔 보여 준다.
 */
@Component
public class StudentAuthenticationProvider implements AuthenticationProvider {

	static final LoginAttemptLimiter.Policy PER_STUDENT = new LoginAttemptLimiter.Policy(3, Duration.ofMinutes(30));
	static final LoginAttemptLimiter.Policy PER_IP = new LoginAttemptLimiter.Policy(10, Duration.ofMinutes(30));
	private static final Pattern STUDENT_ID = Pattern.compile("\\d{8,10}");

	private final StudentAuthenticator authenticator;
	private final LoginAttemptLimiter limiter;

	public StudentAuthenticationProvider(StudentAuthenticator authenticator, LoginAttemptLimiter limiter) {
		this.authenticator = authenticator;
		this.limiter = limiter;
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		String studentId = authentication.getName() == null ? "" : authentication.getName().trim();
		String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
		if (!STUDENT_ID.matcher(studentId).matches() || password.isEmpty()) {
			throw new BadCredentialsException("login.error.format");
		}

		String studentKey = "student:" + studentId;
		String ipKey = "student-ip:" + remoteAddress(authentication);
		if (limiter.isBlocked(studentKey, PER_STUDENT) || limiter.isBlocked(ipKey, PER_IP)) {
			throw new LockedException("login.error.locked");
		}

		try {
			SejongStudent student = authenticator.authenticate(studentId, password);
			limiter.reset(studentKey);
			return UsernamePasswordAuthenticationToken.authenticated(
					new StudentPrincipal(student.studentId(), student.name()), null,
					List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
		}
		catch (SejongAuthException ex) {
			switch (ex.getReason()) {
				case INVALID_CREDENTIALS -> {
					limiter.recordFailure(studentKey, PER_STUDENT);
					limiter.recordFailure(ipKey, PER_IP);
					throw new BadCredentialsException("login.error.invalid");
				}
				case ACCOUNT_RESTRICTED -> throw new LockedException("login.error.restricted");
				default -> throw new AuthenticationServiceException("login.error.unavailable", ex);
			}
		}
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

	static String remoteAddress(Authentication authentication) {
		return authentication.getDetails() instanceof WebAuthenticationDetails details
				? details.getRemoteAddress() : "unknown";
	}

}
