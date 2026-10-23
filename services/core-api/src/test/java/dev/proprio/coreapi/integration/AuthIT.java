package dev.proprio.coreapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.proprio.coreapi.support.Api;
import dev.proprio.coreapi.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

class AuthIT extends IntegrationTest {

  @Test
  void registerLoginAndMe() throws Exception {
    String email = Api.uniqueEmail();
    Api.Tokens reg = api.register(email);

    MvcResult me = api.call(HttpMethod.GET, "/v1/me", reg, null);
    assertThat(me.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = api.json(me);
    assertThat(body.get("email").asText()).isEqualTo(email);
    assertThat(body.get("participantId").asText()).startsWith("P");
    assertThat(body.get("consent").get("trainingUse").asBoolean()).isFalse();

    Api.Tokens login = api.login(email.toUpperCase(), Api.PASSWORD); // citext: case-insensitive
    assertThat(login).isNotNull();
    assertThat(login.refresh()).isNotEqualTo(reg.refresh());
  }

  @Test
  void accessTokenResponseShape() throws Exception {
    MvcResult r =
        api.call(
            HttpMethod.POST,
            "/v1/auth/register",
            null,
            new Api.Creds(Api.uniqueEmail(), Api.PASSWORD));
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    JsonNode b = api.json(r);
    assertThat(b.get("expiresIn").asInt()).isEqualTo(900);
    assertThat(b.get("tokenType").asText()).isEqualTo("Bearer");
    assertThat(b.get("accessToken").asText().split("\\.")).hasSize(3);
  }

  @Test
  void duplicateEmailIs409ProblemJson() throws Exception {
    String email = Api.uniqueEmail();
    api.register(email);
    MvcResult r =
        api.call(HttpMethod.POST, "/v1/auth/register", null, new Api.Creds(email, Api.PASSWORD));
    assertThat(r.getResponse().getStatus()).isEqualTo(409);
    assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
    JsonNode p = api.json(r);
    assertThat(p.get("status").asInt()).isEqualTo(409);
    assertThat(p.has("title")).isTrue();
    assertThat(p.has("type")).isTrue();
  }

  @Test
  void validationErrorsAreProblemDetailsWithFieldErrors() throws Exception {
    MvcResult r =
        api.call(
            HttpMethod.POST, "/v1/auth/register", null, new Api.Creds("not-an-email", "short"));
    assertThat(r.getResponse().getStatus()).isEqualTo(400);
    assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
    JsonNode p = api.json(r);
    assertThat(p.get("errors")).isNotEmpty();
    assertThat(p.get("errors").findValuesAsText("field")).contains("email", "password");
    assertThat(r.getResponse().getContentAsString())
        .doesNotContain("Exception")
        .doesNotContain("at dev.");
  }

  @Test
  void malformedJsonIs400Problem() throws Exception {
    MvcResult r = api.call(HttpMethod.POST, "/v1/auth/login", null, "{not json");
    assertThat(r.getResponse().getStatus()).isEqualTo(400);
    assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
  }

  @Test
  void loginFailureIsGenericAndIdenticalForUnknownEmailAndWrongPassword() throws Exception {
    String email = Api.uniqueEmail();
    api.register(email);
    MvcResult wrong =
        api.call(
            HttpMethod.POST, "/v1/auth/login", null, new Api.Creds(email, "wrong-password-123"));
    MvcResult unknown =
        api.call(
            HttpMethod.POST,
            "/v1/auth/login",
            null,
            new Api.Creds(Api.uniqueEmail(), "wrong-password-123"));
    assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
    assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
    assertThat(wrong.getResponse().getContentAsString())
        .isEqualTo(unknown.getResponse().getContentAsString());
  }

  @Test
  void protectedEndpointsRequireAValidBearerToken() throws Exception {
    MvcResult none = api.call(HttpMethod.GET, "/v1/me", null, null);
    assertThat(none.getResponse().getStatus()).isEqualTo(401);
    assertThat(none.getResponse().getContentType()).startsWith("application/problem+json");

    Api.Tokens t = api.register();
    String tampered = t.access().substring(0, t.access().length() - 3) + "abc";
    MvcResult bad = api.call(HttpMethod.GET, "/v1/me", new Api.Tokens(tampered, ""), null);
    assertThat(bad.getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void refreshRotatesAndReuseRevokesTheWholeFamily() throws Exception {
    Api.Tokens first = api.register();

    MvcResult r1 =
        api.call(
            HttpMethod.POST, "/v1/auth/refresh", null, Map.of("refreshToken", first.refresh()));
    assertThat(r1.getResponse().getStatus()).isEqualTo(200);
    Api.Tokens second = Api.tokens(api.json(r1));
    assertThat(second.refresh()).isNotEqualTo(first.refresh());
    // The new access token works.
    assertThat(api.call(HttpMethod.GET, "/v1/me", second, null).getResponse().getStatus())
        .isEqualTo(200);

    // Replaying the already-rotated token is rejected ...
    MvcResult replay =
        api.call(
            HttpMethod.POST, "/v1/auth/refresh", null, Map.of("refreshToken", first.refresh()));
    assertThat(replay.getResponse().getStatus()).isEqualTo(401);

    // ... and revoked the family: the legitimate newest token no longer works either.
    MvcResult newest =
        api.call(
            HttpMethod.POST, "/v1/auth/refresh", null, Map.of("refreshToken", second.refresh()));
    assertThat(newest.getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void logoutRevokesTheRefreshTokenFamily() throws Exception {
    Api.Tokens t = api.register();
    MvcResult out =
        api.call(HttpMethod.POST, "/v1/auth/logout", t, Map.of("refreshToken", t.refresh()));
    assertThat(out.getResponse().getStatus()).isEqualTo(204);
    MvcResult after =
        api.call(HttpMethod.POST, "/v1/auth/refresh", null, Map.of("refreshToken", t.refresh()));
    assertThat(after.getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void anotherUsersRefreshTokenCannotBeRevokedViaLogout() throws Exception {
    Api.Tokens a = api.register();
    Api.Tokens b = api.register();
    api.call(HttpMethod.POST, "/v1/auth/logout", b, Map.of("refreshToken", a.refresh()));
    MvcResult stillValid =
        api.call(HttpMethod.POST, "/v1/auth/refresh", null, Map.of("refreshToken", a.refresh()));
    assertThat(stillValid.getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void loginIsRateLimitedPerEmail() throws Exception {
    String email = Api.uniqueEmail();
    api.register(email);
    int limited = 0;
    String retryAfter = null;
    for (int i = 0; i < 8; i++) { // email bucket capacity is 5 in the test profile
      MvcResult r =
          api.call(
              HttpMethod.POST, "/v1/auth/login", null, new Api.Creds(email, "wrong-password-123"));
      if (r.getResponse().getStatus() == 429) {
        limited++;
        retryAfter = r.getResponse().getHeader("Retry-After");
        assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
      } else {
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
      }
    }
    assertThat(limited).isGreaterThanOrEqualTo(3);
    assertThat(Integer.parseInt(retryAfter)).isPositive();
    // Even the right password is blocked while limited.
    MvcResult good =
        api.call(HttpMethod.POST, "/v1/auth/login", null, new Api.Creds(email, Api.PASSWORD));
    assertThat(good.getResponse().getStatus()).isEqualTo(429);
  }

  @Test
  void healthIsPublicAndReportsComponents() throws Exception {
    MvcResult r = api.call(HttpMethod.GET, "/health", null, null);
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode h = api.json(r);
    assertThat(h.get("status").asText()).isEqualTo("UP");
    assertThat(h.get("components").get("db").asText()).isEqualTo("UP");
    assertThat(h.get("components").get("rabbit").asText()).isEqualTo("UP");
  }
}
