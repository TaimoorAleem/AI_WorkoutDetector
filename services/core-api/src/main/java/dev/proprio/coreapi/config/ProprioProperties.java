package dev.proprio.coreapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All service configuration, bound from {@code proprio.*} (env-driven, see application.yml). */
@ConfigurationProperties("proprio")
public record ProprioProperties(
    Jwt jwt, Auth auth, Storage storage, Messaging messaging, Outbox outbox, Upload upload) {

  public record Jwt(String issuer, Duration accessTtl, String privateKey, String privateKeyPath) {}

  public record Auth(Duration refreshTtl, Argon2 argon2, RateLimit rateLimit) {}

  public record Argon2(int memoryKib, int iterations, int parallelism) {}

  public record RateLimit(
      int emailCapacity, int emailRefillPerMinute, int ipCapacity, int ipRefillPerMinute) {}

  /**
   * S3-compatible storage. {@code endpoint} is used by the service itself; {@code publicEndpoint}
   * (optional) is the host clients can reach, used only to sign presigned URLs.
   */
  public record Storage(
      String endpoint,
      String publicEndpoint,
      String region,
      String bucket,
      String accessKey,
      String secretKey,
      boolean pathStyle,
      boolean createBucketOnStart) {}

  public record Messaging(String exchange, String analyzedQueue) {}

  public record Outbox(Duration pollInterval, int batchSize) {}

  public record Upload(Duration presignTtl, long maxSizeBytes) {}
}
