package dev.proprio.coreapi.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proprio.coreapi.auth.TokenBucketLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {

  /** Clock whose time the test advances by hand. */
  static class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void allowsUpToCapacityThenBlocksWithRetryAfter() {
    MutableClock clock = new MutableClock();
    var limiter = new TokenBucketLimiter(3, 6, clock); // 6/min = one token per 10 s
    assertThat(limiter.tryAcquire("a")).isZero();
    assertThat(limiter.tryAcquire("a")).isZero();
    assertThat(limiter.tryAcquire("a")).isZero();
    long wait = limiter.tryAcquire("a");
    assertThat(wait).isBetween(1L, 10L);
  }

  @Test
  void refillsOverTime() {
    MutableClock clock = new MutableClock();
    var limiter = new TokenBucketLimiter(1, 6, clock);
    assertThat(limiter.tryAcquire("a")).isZero();
    assertThat(limiter.tryAcquire("a")).isPositive();
    clock.advance(Duration.ofSeconds(11));
    assertThat(limiter.tryAcquire("a")).isZero();
  }

  @Test
  void keysAreIndependent() {
    var limiter = new TokenBucketLimiter(1, 1, new MutableClock());
    assertThat(limiter.tryAcquire("a")).isZero();
    assertThat(limiter.tryAcquire("a")).isPositive();
    assertThat(limiter.tryAcquire("b")).isZero();
  }

  @Test
  void neverExceedsCapacityAfterLongIdle() {
    MutableClock clock = new MutableClock();
    var limiter = new TokenBucketLimiter(2, 60, clock);
    clock.advance(Duration.ofHours(5));
    assertThat(limiter.tryAcquire("a")).isZero();
    assertThat(limiter.tryAcquire("a")).isZero();
    assertThat(limiter.tryAcquire("a")).isPositive();
  }
}
