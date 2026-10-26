package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

	private static final LoginAttemptLimiter.Policy POLICY = new LoginAttemptLimiter.Policy(3, Duration.ofMinutes(30));

	private final MutableClock clock = new MutableClock();
	private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

	@Test
	void blocksAfterMaxFailuresWithinWindow() {
		limiter.recordFailure("k", POLICY);
		limiter.recordFailure("k", POLICY);
		assertThat(limiter.isBlocked("k", POLICY)).isFalse();

		limiter.recordFailure("k", POLICY);
		assertThat(limiter.isBlocked("k", POLICY)).isTrue();
		assertThat(limiter.isBlocked("other", POLICY)).isFalse();
	}

	@Test
	void tryReserveLetsOnlyMaxFailuresThroughEvenWhenConcurrent() throws Exception {
		int threads = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return limiter.tryReserve("k", POLICY);
			}));
		}
		start.countDown();
		int reserved = 0;
		for (Future<Boolean> result : results) {
			reserved += result.get() ? 1 : 0;
		}
		pool.shutdown();

		assertThat(reserved).isEqualTo(POLICY.maxFailures());
		assertThat(limiter.isBlocked("k", POLICY)).isTrue();
		limiter.cancelLast("k");
		assertThat(limiter.tryReserve("k", POLICY)).isTrue();
	}

	@Test
	void unblocksWhenFailuresLeaveWindow() {
		for (int i = 0; i < 3; i++) {
			limiter.recordFailure("k", POLICY);
		}
		clock.advance(Duration.ofMinutes(31));

		assertThat(limiter.isBlocked("k", POLICY)).isFalse();
	}

	@Test
	void resetClearsFailures() {
		for (int i = 0; i < 3; i++) {
			limiter.recordFailure("k", POLICY);
		}
		limiter.reset("k");

		assertThat(limiter.isBlocked("k", POLICY)).isFalse();
	}

	@Test
	void cancelLastRemovesOnlyTheNewestFailure() {
		for (int i = 0; i < 3; i++) {
			limiter.recordFailure("k", POLICY);
		}
		limiter.cancelLast("k");

		assertThat(limiter.isBlocked("k", POLICY)).isFalse();
		limiter.cancelLast("unknown");
	}

	@Test
	void listsBlockedKeysWithPrefixAndWhenTheyUnblock() {
		Instant first = clock.instant();
		limiter.recordFailure("s:1", POLICY);
		clock.advance(Duration.ofMinutes(5));
		limiter.recordFailure("s:1", POLICY);
		limiter.recordFailure("s:1", POLICY);
		limiter.recordFailure("s:2", POLICY);
		for (int i = 0; i < 3; i++) {
			limiter.recordFailure("other:1", POLICY);
		}

		assertThat(limiter.blocked("s:", POLICY))
				.containsExactly(new LoginAttemptLimiter.Blocked("s:1", 3, first.plus(POLICY.window())));

		clock.advance(Duration.ofMinutes(26));
		assertThat(limiter.blocked("s:", POLICY)).isEmpty();
	}

	@Test
	void countsOnlyRecordsWithinWindow() {
		assertThat(limiter.count("k", POLICY)).isZero();
		limiter.recordFailure("k", POLICY);
		clock.advance(Duration.ofMinutes(20));
		limiter.recordFailure("k", POLICY);
		assertThat(limiter.count("k", POLICY)).isEqualTo(2);

		clock.advance(Duration.ofMinutes(11));
		assertThat(limiter.count("k", POLICY)).isEqualTo(1);
	}

	@Test
	void allowOneMoreAttemptLeavesOneTryBeforeBlockingAgain() {
		assertThat(limiter.allowOneMoreAttempt("k", POLICY)).isFalse();
		for (int i = 0; i < 3; i++) {
			limiter.recordFailure("k", POLICY);
		}

		assertThat(limiter.allowOneMoreAttempt("k", POLICY)).isTrue();
		assertThat(limiter.isBlocked("k", POLICY)).isFalse();
		assertThat(limiter.allowOneMoreAttempt("k", POLICY)).isFalse();
		assertThat(limiter.tryReserve("k", POLICY)).isTrue();
		assertThat(limiter.isBlocked("k", POLICY)).isTrue();
	}

	@Test
	void removeExpiredDropsOldKeysOnly() {
		limiter.recordFailure("old", POLICY);
		clock.advance(Duration.ofMinutes(31));
		limiter.recordFailure("new", POLICY);
		limiter.recordFailure("new", POLICY);
		limiter.recordFailure("new", POLICY);

		limiter.removeExpired(Duration.ofMinutes(30));

		assertThat(limiter.isBlocked("new", POLICY)).isTrue();
		limiter.recordFailure("old", POLICY);
		assertThat(limiter.isBlocked("old", POLICY)).isFalse();
	}

	private static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-03-04T01:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

	}

}
