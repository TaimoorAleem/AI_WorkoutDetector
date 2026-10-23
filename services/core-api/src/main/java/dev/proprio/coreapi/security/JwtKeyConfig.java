package dev.proprio.coreapi.security;

import dev.proprio.coreapi.config.ProprioProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * RS256 signing key. Production: a PKCS#8 PEM private key from {@code JWT_PRIVATE_KEY} or {@code
 * JWT_PRIVATE_KEY_PATH} (the public key is derived from it). Only the {@code dev} and {@code test}
 * profiles may fall back to an ephemeral generated key pair; any other profile fails to start.
 */
@Configuration
public class JwtKeyConfig {

  private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

  @Bean
  public KeyPair jwtKeyPair(ProprioProperties props, Environment env) throws Exception {
    String pem = props.jwt().privateKey();
    if (pem == null || pem.isBlank()) {
      String path = props.jwt().privateKeyPath();
      if (path != null && !path.isBlank()) {
        pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
      }
    }
    if (pem != null && !pem.isBlank()) {
      return fromPkcs8Pem(pem);
    }
    if (env.acceptsProfiles(Profiles.of("dev", "test"))) {
      log.warn("No JWT key configured: generating an ephemeral RS256 key pair (dev/test only).");
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    }
    throw new IllegalStateException(
        "JWT signing key missing: set JWT_PRIVATE_KEY or JWT_PRIVATE_KEY_PATH (PKCS#8 PEM).");
  }

  public static KeyPair fromPkcs8Pem(String pem) throws IOException, GeneralSecurityException {
    String base64 =
        pem.replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    KeyFactory kf = KeyFactory.getInstance("RSA");
    var privateKey =
        kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
      throw new IllegalStateException("JWT private key must be an RSA CRT key.");
    }
    RSAPublicKey publicKey =
        (RSAPublicKey)
            kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    return new KeyPair(publicKey, crt);
  }
}
