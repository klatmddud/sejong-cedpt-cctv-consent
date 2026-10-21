package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
