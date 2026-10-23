package dev.proprio.coreapi.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import dev.proprio.coreapi.config.ProprioProperties;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Issues short-lived RS256 access tokens and provides the matching decoder. */
@Service
public class JwtService {

  public record AccessToken(String value, long expiresInSeconds) {}

  private final JwtEncoder encoder;
  private final JwtDecoder decoder;
  private final String issuer;
  private final Duration ttl;
  private final Clock clock;
  private final String keyId;

  public JwtService(KeyPair keyPair, ProprioProperties props, Clock clock) {
    this.issuer = props.jwt().issuer();
    this.ttl = props.jwt().accessTtl();
    this.clock = clock;
    RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
    this.keyId = UUID.nameUUIDFromBytes(pub.getEncoded()).toString();
    RSAKey jwk =
        new RSAKey.Builder(pub)
            .privateKey((RSAPrivateKey) keyPair.getPrivate())
            .keyID(keyId)
            .build();
    this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    NimbusJwtDecoder nimbus =
        NimbusJwtDecoder.withPublicKey(pub).signatureAlgorithm(SignatureAlgorithm.RS256).build();
    OAuth2TokenValidator<Jwt> validator =
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ofSeconds(30)),
            JwtValidators.createDefaultWithIssuer(issuer));
    nimbus.setJwtValidator(validator);
    this.decoder = nimbus;
  }

  public AccessToken issue(UUID userId) {
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .id(UUID.randomUUID().toString())
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new AccessToken(token, ttl.toSeconds());
  }

  public JwtDecoder decoder() {
    return decoder;
  }
}
