package dev.proprio.coreapi.auth;

import dev.proprio.coreapi.config.ProprioProperties;
import dev.proprio.coreapi.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque 256-bit refresh tokens, stored only as SHA-256 hashes. Every use rotates the token within
 * its family; presenting an already-rotated token is treated as theft and revokes the whole family.
 */
@Service
public class RefreshTokenService {

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** A freshly issued raw token plus the user it belongs to. */
  public record Issued(String rawToken, UUID userId) {}

  private final RefreshTokenRepository tokens;
  private final ProprioProperties props;
  private final Clock clock;

  public RefreshTokenService(RefreshTokenRepository tokens, ProprioProperties props, Clock clock) {
    this.tokens = tokens;
    this.props = props;
    this.clock = clock;
  }

  /** Starts a new family (login / register). */
  @Transactional
  public String issueNewFamily(UUID userId) {
    return issue(userId, UUID.randomUUID());
  }

  /**
   * Rotates {@code rawToken}. {@code noRollbackFor}: the family revocation on reuse must commit
   * even though we then answer 401.
   */
  @Transactional(noRollbackFor = ApiException.class)
  public Issued rotate(String rawToken) {
    Instant now = clock.instant();
    RefreshTokenEntity t =
        tokens
            .findByTokenHash(hash(rawToken))
            .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token."));
    if (t.getRevokedAt() != null) {
      throw ApiException.unauthorized("Invalid refresh token.");
    }
    if (t.getRotatedAt() != null) {
      int revoked = tokens.revokeFamily(t.getFamilyId(), now);
      log.warn(
          "Refresh token reuse detected; revoked family {} ({} tokens)", t.getFamilyId(), revoked);
      throw ApiException.unauthorized("Invalid refresh token.");
    }
    if (!t.getExpiresAt().isAfter(now)) {
      throw ApiException.unauthorized("Invalid refresh token.");
    }
    t.markRotated(now);
    String next = issue(t.getUserId(), t.getFamilyId());
    return new Issued(next, t.getUserId());
  }

  /** Revokes the family of {@code rawToken} if it belongs to {@code userId}; silent otherwise. */
  @Transactional
  public void revokeFamilyOf(String rawToken, UUID userId) {
    tokens
        .findByTokenHashAndUserId(hash(rawToken), userId)
        .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), clock.instant()));
  }

  private String issue(UUID userId, UUID familyId) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = clock.instant();
    tokens.save(
        new RefreshTokenEntity(
            userId, familyId, hash(raw), now, now.plus(props.auth().refreshTtl())));
    return raw;
  }

  static String hash(String raw) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
