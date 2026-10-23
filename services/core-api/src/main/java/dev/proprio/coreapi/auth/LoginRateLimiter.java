package dev.proprio.coreapi.auth;

import dev.proprio.coreapi.config.ProprioProperties;
import dev.proprio.coreapi.web.ApiException;
import java.time.Clock;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Login rate limiting per email and per client IP (every attempt counts). */
@Component
public class LoginRateLimiter {

  private final TokenBucketLimiter byEmail;
  private final TokenBucketLimiter byIp;

  public LoginRateLimiter(ProprioProperties props, Clock clock) {
    var rl = props.auth().rateLimit();
    this.byEmail = new TokenBucketLimiter(rl.emailCapacity(), rl.emailRefillPerMinute(), clock);
    this.byIp = new TokenBucketLimiter(rl.ipCapacity(), rl.ipRefillPerMinute(), clock);
  }

  /** Throws a 429 problem when either the email or the IP is over its budget. */
  public void check(String email, String ip) {
    long ipWait = byIp.tryAcquire(ip == null ? "unknown" : ip);
    long emailWait = byEmail.tryAcquire(email.toLowerCase(Locale.ROOT).trim());
    long wait = Math.max(ipWait, emailWait);
    if (wait > 0) {
      throw ApiException.tooManyRequests(wait);
    }
  }
}
