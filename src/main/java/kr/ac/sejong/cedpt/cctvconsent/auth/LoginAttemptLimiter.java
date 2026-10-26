package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
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

	/**
	 * 지금 막힌 키 하나.
	 *
	 * @param unblocksAt 실패 기록이 기간을 지나 한도 아래로 내려가는 시각(그때 다시 시도할 수 있다)
	 */
	public record Blocked(String key, int failures, Instant unblocksAt) {
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

	/** 기간 안에 남은 기록 수. */
	public int count(String key, Policy policy) {
		Deque<Instant> recent = failures.get(key);
		if (recent == null) {
			return 0;
		}
		synchronized (recent) {
			prune(recent, policy);
			return recent.size();
		}
	}

	/** prefix로 시작하는 키 중 지금 막힌 것. 먼저 풀리는 순서다. */
	public List<Blocked> blocked(String prefix, Policy policy) {
		List<Blocked> blocked = new ArrayList<>();
		failures.forEach((key, recent) -> {
			if (!key.startsWith(prefix)) {
				return;
			}
			synchronized (recent) {
				prune(recent, policy);
				if (recent.size() >= policy.maxFailures()) {
					// 한도 아래로 내려가려면 이 기록까지 기간이 지나야 한다
					Instant last = recent.stream().skip(recent.size() - policy.maxFailures()).findFirst().orElseThrow();
					blocked.add(new Blocked(key, recent.size(), last.plus(policy.window())));
				}
			}
		});
		blocked.sort(Comparator.comparing(Blocked::unblocksAt));
		return blocked;
	}

	/**
	 * 막힌 키를 풀되 한 번만 더 시도할 수 있게 한다. 실패 기록을 모두 지우지 않고 오래된 것부터 지워 한도보다 하나 적게 남긴다.
	 *
	 * @return 막혀 있어서 풀었으면 true
	 */
	public boolean allowOneMoreAttempt(String key, Policy policy) {
		Deque<Instant> recent = failures.get(key);
		if (recent == null) {
			return false;
		}
		synchronized (recent) {
			prune(recent, policy);
			if (recent.size() < policy.maxFailures()) {
				return false;
			}
			while (recent.size() > policy.maxFailures() - 1) {
				recent.removeFirst();
			}
			return true;
		}
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
