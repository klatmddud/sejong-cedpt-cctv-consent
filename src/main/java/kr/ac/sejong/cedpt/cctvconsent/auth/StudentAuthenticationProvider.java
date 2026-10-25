package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import kr.ac.sejong.cedpt.cctvconsent.roster.EnrollmentSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * 학생 로그인. 세종대 포털로 학번/비밀번호를 확인한다.
 *
 * 세종대 포털은 5회 실패하면 비밀번호를 초기화해야 하므로, 우리 서버를 통한 실패는 학번당 24시간에 3회로 막는다
 * (짧은 기간이면 기간이 지날 때마다 다시 시도할 수 있어 포털 실패가 5회를 넘는다).
 * 같은 IP에서 여러 학번을 시도하는 것도 30분에 10회로 막는다.
 * 남의 학번으로 일부러 틀려 잠그는 공격은 학번별 제한만으로는 막을 수 없다(잠그는 것이 목적이므로). 서버 전체 실패가
 * 10분에 SPIKE개를 넘으면 공격으로 보고, 그동안은 IP당 한도를 PER_IP_UNDER_ATTACK으로 줄여 IP 하나로 잠글 수 있는
 * 학번 수를 줄이고 감사 로그에 남긴다. 완전히 막으려면 CAPTCHA가 필요하다.
 * 학번별 실패는 포털에 묻기 전에 확인과 함께 미리 센다(tryReserve). 동시에 보낸 요청이 모두 한도 확인을 통과하지 못하게 하고, 같은 학번 시도는
 * 한 번에 하나만, 포털 호출은 서버 전체에서 동시에 MAX_PORTAL_CALLS개까지만 보낸다(포털 응답을 기다리는 스레드가
 * 쌓이지 않게). 비밀번호가 틀린 것이 아니면(포털 장애 등) 미리 센 실패를 되돌린다.
 * IP별 실패는 미리 세지 않고 포털이 응답한 뒤에 센다(미리 세면 한 강의실 와이파이에서 함께 로그인할 때 진행 중인
 * 로그인만으로 한도가 찬다). 비밀번호 오류뿐 아니라 계정 제한·포털 장애 응답도 IP 한도에 센다. 포털까지 가는 시도를
 * IP당으로 묶어, 학번만 바꿔 비밀번호 외 실패를 쏟아부어 스레드·포털 슬롯을 고갈시키는 공격을 막기 위해서다.
 * 그 대가로 포털 장애가 길어지면 같은 IP가 30분에 10회로 잠시 막힐 수 있고, 동시에 몰리면 IP 한도를 포털 동시 호출 수만큼 넘을 수 있다.
 * 예외 메시지는 messages.properties의 코드이며 로그인 화면이 학생이 고른 언어로 바꿔 보여 준다.
 * 로그인에 성공하면 sjpt에서 받은 수강 과목을 반영한다. 반영에 실패해도 로그인은 성공시키고 이전 수강 정보로 보여 준다.
 */
@Component
public class StudentAuthenticationProvider implements AuthenticationProvider {

	static final LoginAttemptLimiter.Policy PER_STUDENT = new LoginAttemptLimiter.Policy(3, Duration.ofHours(24));
	static final LoginAttemptLimiter.Policy PER_IP = new LoginAttemptLimiter.Policy(10, Duration.ofMinutes(30));
	static final LoginAttemptLimiter.Policy SPIKE = new LoginAttemptLimiter.Policy(30, Duration.ofMinutes(10));
	static final LoginAttemptLimiter.Policy PER_IP_UNDER_ATTACK = new LoginAttemptLimiter.Policy(2, PER_IP.window());
	private static final String ALL_STUDENTS = "student-all";
	static final int MAX_PORTAL_CALLS = 8;
	// 한 반이 함께 로그인하면 잠깐 몰린다. 바로 "잠시 후 다시"를 보이지 않고 앞사람 포털 응답(보통 1~2초)을 기다린다
	static final Duration PORTAL_WAIT = Duration.ofSeconds(3);
	private static final Pattern STUDENT_ID = Pattern.compile("\\d{8,10}");

	private static final Logger log = LoggerFactory.getLogger(StudentAuthenticationProvider.class);
	private static final Logger audit = LoggerFactory.getLogger("audit");

	private final StudentAuthenticator authenticator;
	private final LoginAttemptLimiter limiter;
	private final EnrollmentSync enrollmentSync;
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final Semaphore portalCalls = new Semaphore(MAX_PORTAL_CALLS);
	private final AtomicReference<Instant> lastSpikeAlert = new AtomicReference<>(Instant.EPOCH);

	public StudentAuthenticationProvider(StudentAuthenticator authenticator, LoginAttemptLimiter limiter,
			EnrollmentSync enrollmentSync) {
		this.authenticator = authenticator;
		this.limiter = limiter;
		this.enrollmentSync = enrollmentSync;
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
		if (!inFlight.add(studentKey)) {
			throw new LockedException("login.error.busy");
		}
		StudentLogin login;
		try {
			// 포털에 묻기 전에 실패로 센다. 성공하거나 비밀번호 문제가 아니면 되돌린다
			if (!limiter.tryReserve(studentKey, PER_STUDENT)) {
				throw new LockedException("login.error.locked");
			}
			LoginAttemptLimiter.Policy ipPolicy = limiter.isBlocked(ALL_STUDENTS, SPIKE) ? PER_IP_UNDER_ATTACK : PER_IP;
			if (limiter.isBlocked(ipKey, ipPolicy)) {
				limiter.cancelLast(studentKey);
				throw new LockedException("login.error.locked");
			}
			if (!acquirePortalCall()) {
				limiter.cancelLast(studentKey);
				throw new AuthenticationServiceException("login.error.busy");
			}
			try {
				login = askPortal(studentId, password, studentKey, ipKey);
			}
			finally {
				portalCalls.release();
			}
		}
		finally {
			inFlight.remove(studentKey);
		}

		SejongStudent student = login.student();
		return UsernamePasswordAuthenticationToken.authenticated(
				new StudentPrincipal(student.studentId(), student.name(), syncCourses(login)), null,
				List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
	}

	private boolean acquirePortalCall() {
		try {
			return portalCalls.tryAcquire(PORTAL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** studentKey에 실패가 하나 미리 기록된 상태로 부른다. ipKey에는 포털이 실패로 응답했을 때(이유 무관) 기록한다. */
	private StudentLogin askPortal(String studentId, String password, String studentKey, String ipKey) {
		try {
			StudentLogin login = authenticator.authenticate(studentId, password);
			limiter.reset(studentKey);
			return login;
		}
		catch (SejongAuthException ex) {
			// 포털까지 간 실패는 이유와 무관하게 IP 한도와 SPIKE에 센다. 예전엔 비밀번호 오류만 셌는데,
			// 포털이 '계정 제한/장애'로 응답하는 시도를 학번만 바꿔 쏟아부으면 스레드와 포털 슬롯을 계속
			// 붙잡으면서도 어떤 IP 한도에도 걸리지 않았다(스레드 고갈 DoS). 절충: 포털 장애가 길어지면
			// 같은 IP(강의실 와이파이)가 30분에 10회로 잠시 막힐 수 있다.
			limiter.recordFailure(ipKey, PER_IP);
			recordStudentFailure();
			if (ex.getReason() == SejongAuthException.Reason.INVALID_CREDENTIALS) {
				throw new BadCredentialsException("login.error.invalid");
			}
			// 비밀번호 오류가 아니면 학번당 한도는 되돌린다(포털 장애로 한 학생이 24시간 잠기지 않게).
			limiter.cancelLast(studentKey);
			if (ex.getReason() == SejongAuthException.Reason.ACCOUNT_RESTRICTED) {
				throw new LockedException("login.error.restricted");
			}
			throw new AuthenticationServiceException("login.error.unavailable", ex);
		}
		catch (RuntimeException ex) {
			limiter.cancelLast(studentKey);
			throw ex;
		}
	}

	/** 서버 전체의 비밀번호 실패를 센다. 공격으로 보이기 시작하면 10분에 한 번만 감사 로그에 남긴다. */
	private void recordStudentFailure() {
		limiter.recordFailure(ALL_STUDENTS, SPIKE);
		if (limiter.isBlocked(ALL_STUDENTS, SPIKE)) {
			Instant now = Instant.now();
			Instant last = lastSpikeAlert.get();
			if (last.isBefore(now.minus(SPIKE.window())) && lastSpikeAlert.compareAndSet(last, now)) {
				audit.warn("학생 로그인 실패 급증: {}분에 {}회 이상. IP당 한도를 {}회로 줄인다", SPIKE.window().toMinutes(),
						SPIKE.maxFailures(), PER_IP_UNDER_ATTACK.maxFailures());
			}
		}
	}

	/** 오래된 실패 기록을 매시간 정리한다. */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
	void removeExpiredFailures() {
		limiter.removeExpired(PER_STUDENT.window());
	}

	/** @return 수강 과목을 받아 반영했는지 */
	private boolean syncCourses(StudentLogin login) {
		if (!login.coursesLoaded()) {
			return false;
		}
		List<EnrollmentSync.EnrolledCourse> courses = login.courses().stream()
				.map(course -> new EnrollmentSync.EnrolledCourse(course.code(), course.name(), course.professors(),
						course.times(), course.rooms()))
				.toList();
		try {
			enrollmentSync.sync(login.student().studentId(), login.student().name(), login.term().year(),
					String.valueOf(login.term().term()), courses);
			return true;
		}
		catch (RuntimeException ex) {
			// DB 제약 오류 메시지에는 학번이 들어갈 수 있어 예외 종류만 남긴다
			log.warn("수강 과목 반영 실패: {}", ex.getClass().getName());
			log.debug("수강 과목 반영 실패", ex);
			return false;
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
