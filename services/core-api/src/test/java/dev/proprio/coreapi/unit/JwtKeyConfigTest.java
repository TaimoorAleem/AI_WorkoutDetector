package dev.proprio.coreapi.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proprio.coreapi.config.ProprioProperties;
import dev.proprio.coreapi.security.JwtKeyConfig;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtKeyConfigTest {

  static ProprioProperties propsWithKey(String pem) {
    return new ProprioProperties(
        new ProprioProperties.Jwt("iss", null, pem, ""), null, null, null, null, null);
  }

  @Test
  void loadsPkcs8PemAndDerivesThePublicKey() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair original = gen.generateKeyPair();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(original.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";

    KeyPair loaded = new JwtKeyConfig().jwtKeyPair(propsWithKey(pem), new MockEnvironment());
    assertThat(loaded.getPublic()).isEqualTo(original.getPublic());

    // Single-line env var form with literal \n escapes works too.
    KeyPair fromEnvStyle =
        new JwtKeyConfig()
            .jwtKeyPair(propsWithKey(pem.trim().replace("\n", "\\n")), new MockEnvironment());
    assertThat(fromEnvStyle.getPublic()).isEqualTo(original.getPublic());
  }

  @Test
  void devAndTestProfilesMayGenerateAnEphemeralKey() throws Exception {
    for (String profile : new String[] {"dev", "test"}) {
      MockEnvironment env = new MockEnvironment();
      env.setActiveProfiles(profile);
      assertThat(new JwtKeyConfig().jwtKeyPair(propsWithKey(""), env).getPrivate()).isNotNull();
    }
  }

  @Test
  void otherProfilesFailFastWithoutAKey() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    assertThatThrownBy(() -> new JwtKeyConfig().jwtKeyPair(propsWithKey(""), prod))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_PRIVATE_KEY");
    assertThatThrownBy(() -> new JwtKeyConfig().jwtKeyPair(propsWithKey(""), new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class);
  }
}
