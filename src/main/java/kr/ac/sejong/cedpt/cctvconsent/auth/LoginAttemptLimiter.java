package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 키(학번, IP 등)별 로그인 실패 횟수를 일정 시간 동안 센다. 서버 메모리에만 두므로 재시작하면 초기화된다.
 */
@Component
public class LoginAttemptLimiter {

	public record Policy(int maxFailures, Duration window) {
	}

	private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
	private final Clock clock;

	@Autowired
	public LoginAttemptLimiter() {
		this(Clock.systemUTC());
	}

	LoginAttemptLimiter(Clock clock) {
		this.clock = clock;
	}

	public boolean isBlocked(String key, Policy policy) {
		Deque<Instant> recent = failures.get(key);
		if (recent == null) {
			return false;
		}
		synchronized (recent) {
			prune(recent, policy);
			return recent.size() >= policy.maxFailures();
		}
	}

	/**
	 * 막혀 있지 않으면 실패 하나를 미리 기록하고 true를 돌려준다. 확인과 기록을 한 번에 해서, 동시에 들어온 요청들이 모두
	 * isBlocked를 통과한 뒤 기록하는 바람에 한도를 넘는 일이 없게 한다. 시도가 성공하면 reset, 실패가 아니었으면
	 * cancelLast로 되돌린다.
	 */
	public boolean tryReserve(String key, Policy policy) {
		Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (recent) {
			prune(recent, policy);
			if (recent.size() >= policy.maxFailures()) {
				return false;
			}
			recent.addLast(clock.instant());
			return true;
		}
	}

	public void recordFailure(String key, Policy policy) {
		Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (recent) {
			prune(recent, policy);
			recent.addLast(clock.instant());
		}
	}

	public void reset(String key) {
		failures.remove(key);
	}

	/** 가장 최근에 기록한 실패 하나를 지운다. 시도 전에 미리 기록한 실패가 실패가 아니었을 때 쓴다. */
	public void cancelLast(String key) {
		Deque<Instant> recent = failures.get(key);
		if (recent != null) {
			synchronized (recent) {
				recent.pollLast();
			}
		}
	}

	/** 기간이 지난 기록만 남은 키를 지운다(맵이 계속 커지지 않게). */
	public void removeExpired(Duration longestWindow) {
		Instant threshold = clock.instant().minus(longestWindow);
		failures.values().forEach(recent -> {
			synchronized (recent) {
				while (!recent.isEmpty() && recent.peekFirst().isBefore(threshold)) {
					recent.removeFirst();
				}
			}
		});
		failures.values().removeIf(recent -> {
			synchronized (recent) {
				return recent.isEmpty();
			}
		});
	}

	private void prune(Deque<Instant> recent, Policy policy) {
		Instant threshold = clock.instant().minus(policy.window());
		while (!recent.isEmpty() && recent.peekFirst().isBefore(threshold)) {
			recent.removeFirst();
		}
	}

}
