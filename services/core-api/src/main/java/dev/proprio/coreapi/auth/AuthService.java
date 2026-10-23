package dev.proprio.coreapi.auth;

import dev.proprio.coreapi.api.model.TokenPair;
import dev.proprio.coreapi.security.JwtService;
import dev.proprio.coreapi.user.ConsentEntity;
import dev.proprio.coreapi.user.ConsentRepository;
import dev.proprio.coreapi.user.UserEntity;
import dev.proprio.coreapi.user.UserRepository;
import dev.proprio.coreapi.web.ApiException;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final UserRepository users;
  private final ConsentRepository consents;
  private final PasswordEncoder encoder;
  private final JwtService jwt;
  private final RefreshTokenService refreshTokens;
  private final LoginRateLimiter rateLimiter;
  private final Clock clock;

  /** Compared against when the email is unknown so that login time doesn't reveal existence. */
  private final String dummyHash;

  public AuthService(
      UserRepository users,
      ConsentRepository consents,
      PasswordEncoder encoder,
      JwtService jwt,
      RefreshTokenService refreshTokens,
      LoginRateLimiter rateLimiter,
      Clock clock) {
    this.users = users;
    this.consents = consents;
    this.encoder = encoder;
    this.jwt = jwt;
    this.refreshTokens = refreshTokens;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.dummyHash = encoder.encode(UUID.randomUUID().toString());
  }

  @Transactional
  public TokenPair register(String email, String password, String participantId) {
    String normalized = normalize(email);
    if (users.existsByEmail(normalized)) {
      throw ApiException.conflict("An account with this email already exists.");
    }
    UUID id = UUID.randomUUID();
    String participant =
        participantId != null && !participantId.isBlank()
            ? participantId
            : "P" + id.toString().replace("-", "").substring(0, 8);
    try {
      users.saveAndFlush(
          new UserEntity(id, normalized, encoder.encode(password), participant, clock.instant()));
    } catch (DataIntegrityViolationException e) {
      throw ApiException.conflict("An account with this email already exists.");
    }
    consents.save(new ConsentEntity(id, false, clock.instant()));
    return pair(id, refreshTokens.issueNewFamily(id));
  }

  @Transactional
  public TokenPair login(String email, String password, String clientIp) {
    rateLimiter.check(email, clientIp);
    String normalized = normalize(email);
    UserEntity user = users.findByEmailAndDeletedAtIsNull(normalized).orElse(null);
    boolean ok = encoder.matches(password, user != null ? user.getPasswordHash() : dummyHash);
    if (user == null || !ok) {
      throw ApiException.unauthorized("Invalid email or password.");
    }
    return pair(user.getId(), refreshTokens.issueNewFamily(user.getId()));
  }

  @Transactional(noRollbackFor = ApiException.class)
  public TokenPair refresh(String refreshToken) {
    RefreshTokenService.Issued issued = refreshTokens.rotate(refreshToken);
    if (!users.existsByIdAndDeletedAtIsNull(issued.userId())) {
      throw ApiException.unauthorized("Invalid refresh token.");
    }
    return pair(issued.userId(), issued.rawToken());
  }

  public void logout(String refreshToken, UUID userId) {
    refreshTokens.revokeFamilyOf(refreshToken, userId);
  }

  private TokenPair pair(UUID userId, String refreshToken) {
    JwtService.AccessToken access = jwt.issue(userId);
    return new TokenPair(
        access.value(),
        refreshToken,
        (int) access.expiresInSeconds(),
        TokenPair.TokenTypeEnum.BEARER);
  }

  private static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
