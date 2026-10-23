package dev.proprio.coreapi.auth;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket per key. Fine for a single-instance MVP; a shared store (Redis) is needed
 * once core-api runs with several replicas.
 */
public class TokenBucketLimiter {

  private static final int PRUNE_THRESHOLD = 10_000;

  private static final class Bucket {
    double tokens;
    long lastRefillNanos;

    Bucket(double tokens, long now) {
      this.tokens = tokens;
      this.lastRefillNanos = now;
    }
  }

  private final int capacity;
  private final double refillPerNano;
  private final Clock clock;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  public TokenBucketLimiter(int capacity, int refillPerMinute, Clock clock) {
    this.capacity = capacity;
    this.refillPerNano = refillPerMinute / 60_000_000_000.0;
    this.clock = clock;
  }

  /** Takes one token. Returns 0 when allowed, otherwise the seconds until a token is available. */
  public long tryAcquire(String key) {
    long now = nanos();
    if (buckets.size() > PRUNE_THRESHOLD) {
      prune(now);
    }
    Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
    synchronized (b) {
      double refilled = (now - b.lastRefillNanos) * refillPerNano;
      b.tokens = Math.min(capacity, b.tokens + refilled);
      b.lastRefillNanos = now;
      if (b.tokens >= 1.0) {
        b.tokens -= 1.0;
        return 0;
      }
      double missing = 1.0 - b.tokens;
      return Math.max(1L, (long) Math.ceil(missing / refillPerNano / 1_000_000_000.0));
    }
  }

  private void prune(long now) {
    buckets
        .entrySet()
        .removeIf(
            e -> {
              Bucket b = e.getValue();
              synchronized (b) {
                return b.tokens + (now - b.lastRefillNanos) * refillPerNano >= capacity;
              }
            });
  }

  private long nanos() {
    return clock.instant().getEpochSecond() * 1_000_000_000L + clock.instant().getNano();
  }
}
