package dev.proprio.coreapi.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proprio.coreapi.config.ProprioProperties;
import dev.proprio.coreapi.security.JwtService;
import dev.proprio.coreapi.security.SecurityConfig;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

class PasswordAndJwtTest {

  static KeyPair keys;

  @BeforeAll
  static void generateKeys() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    keys = gen.generateKeyPair();
  }

  static ProprioProperties props() {
    return new ProprioProperties(
        new ProprioProperties.Jwt("https://issuer.test", Duration.ofMinutes(15), "", ""),
        new ProprioProperties.Auth(
            Duration.ofDays(30),
            new ProprioProperties.Argon2(1024, 1, 1),
            new ProprioProperties.RateLimit(5, 5, 30, 30)),
        null,
        null,
        null,
        null);
  }

  @Test
  void passwordsAreHashedWithArgon2idAndSalted() {
    PasswordEncoder encoder = new SecurityConfig().passwordEncoder(props());
    String h1 = encoder.encode("correct horse battery");
    String h2 = encoder.encode("correct horse battery");
    assertThat(h1).startsWith("$argon2id$").isNotEqualTo(h2);
    assertThat(encoder.matches("correct horse battery", h1)).isTrue();
    assertThat(encoder.matches("wrong password!!", h1)).isFalse();
    assertThat(h1).doesNotContain("correct");
  }

  @Test
  void productionArgon2DefaultsMeetOwaspMinimum() {
    // application.yml defaults: m=19456 KiB (19 MiB), t=2, p=1
    PasswordEncoder encoder =
        new SecurityConfig()
            .passwordEncoder(
                new ProprioProperties(
                    null,
                    new ProprioProperties.Auth(
                        null, new ProprioProperties.Argon2(19456, 2, 1), null),
                    null,
                    null,
                    null,
                    null));
    assertThat(encoder.encode("x".repeat(12))).contains("m=19456,t=2,p=1");
  }

  @Test
  void accessTokenIsRs256WithFifteenMinuteLifetime() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    JwtService jwt = new JwtService(keys, props(), clock);
    UUID user = UUID.randomUUID();
    JwtService.AccessToken token = jwt.issue(user);

    String header = new String(Base64.getUrlDecoder().decode(token.value().split("\\.")[0]));
    assertThat(header).contains("\"alg\":\"RS256\"").contains("\"kid\"");
    assertThat(token.expiresInSeconds()).isEqualTo(900);

    // The decoder validates relative to the real clock, so decode with a service at "now".
    JwtService live = new JwtService(keys, props(), Clock.systemUTC());
    Jwt decoded = live.decoder().decode(live.issue(user).value());
    assertThat(decoded.getSubject()).isEqualTo(user.toString());
    assertThat(decoded.getIssuer().toString()).isEqualTo("https://issuer.test");
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void expiredTamperedAndForeignTokensAreRejected() throws Exception {
    JwtService jwt = new JwtService(keys, props(), Clock.systemUTC());
    UUID user = UUID.randomUUID();

    // Issued an hour ago -> expired (beyond the 30 s skew).
    Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
    String expired = new JwtService(keys, props(), past).issue(user).value();
    assertThatThrownBy(() -> jwt.decoder().decode(expired)).isInstanceOf(BadJwtException.class);

    // Flip a character of the signature.
    String good = jwt.issue(user).value();
    String tampered =
        good.substring(0, good.length() - 4) + (good.endsWith("AAAA") ? "BBBB" : "AAAA");
    assertThatThrownBy(() -> jwt.decoder().decode(tampered)).isInstanceOf(BadJwtException.class);

    // Signed by a different key.
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    String foreign =
        new JwtService(gen.generateKeyPair(), props(), Clock.systemUTC()).issue(user).value();
    assertThatThrownBy(() -> jwt.decoder().decode(foreign)).isInstanceOf(BadJwtException.class);
  }
}
