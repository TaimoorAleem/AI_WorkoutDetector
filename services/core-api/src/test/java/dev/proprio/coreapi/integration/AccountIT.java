package dev.proprio.coreapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proprio.coreapi.events.OutboxRepository;
import dev.proprio.coreapi.session.StorageService;
import dev.proprio.coreapi.support.Api;
import dev.proprio.coreapi.support.Golden;
import dev.proprio.coreapi.support.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;

class AccountIT extends IntegrationTest {

  @Autowired StorageService storage;
  @Autowired OutboxRepository outbox;
  @Autowired JdbcClient jdbc;

  @Test
  void consentIsAnAppendOnlyHistoryAndCurrentIsTheLatest() throws Exception {
    Api.Tokens t = api.register();
    MvcResult on = api.call(HttpMethod.PUT, "/v1/me/consent", t, Map.of("trainingUse", true));
    assertThat(on.getResponse().getStatus()).isEqualTo(200);
    assertThat(api.json(on).get("trainingUse").asBoolean()).isTrue();

    JsonNode me = api.json(api.call(HttpMethod.GET, "/v1/me", t, null));
    assertThat(me.get("consent").get("trainingUse").asBoolean()).isTrue();

    api.call(HttpMethod.PUT, "/v1/me/consent", t, Map.of("trainingUse", false));
    JsonNode export = api.json(api.call(HttpMethod.GET, "/v1/me/export", t, null));
    assertThat(export.get("consentHistory")).hasSize(3); // registration default + two changes
    assertThat(export.get("user").get("consent").get("trainingUse").asBoolean()).isFalse();
    assertThat(api.call(HttpMethod.PUT, "/v1/me/consent", t, "{}").getResponse().getStatus())
        .isEqualTo(400);
  }

  @Test
  void exportContainsEverythingAndDeleteRemovesRowsObjectsAndAccess() throws Exception {
    String email = Api.uniqueEmail();
    Api.Tokens t = api.register(email);
    UUID userId = api.userId(t);
    UUID sessionId = UUID.randomUUID();
    byte[] zip = Golden.gymZip();
    JsonNode ticket = api.json(api.createSession(t, sessionId, zip));
    assertThat(Api.putToStorage(ticket, zip, true)).isEqualTo(200);
    assertThat(
            api.call(HttpMethod.POST, "/v1/sessions/" + sessionId + "/complete", t, null)
                .getResponse()
                .getStatus())
        .isEqualTo(202);
    ObjectNode labels = (ObjectNode) Golden.gymLabels().deepCopy();
    labels.put("session_id", sessionId.toString());
    api.call(HttpMethod.PUT, "/v1/sessions/" + sessionId + "/labels", t, labels.toString());

    JsonNode export = api.json(api.call(HttpMethod.GET, "/v1/me/export", t, null));
    assertThat(export.get("user").get("email").asText()).isEqualTo(email);
    JsonNode item = export.get("sessions").get(0);
    assertThat(item.get("session").get("sessionId").asText()).isEqualTo(sessionId.toString());
    assertThat(item.get("labels").get("labels_format").asInt()).isEqualTo(1);
    assertThat(Api.get(item.get("rawDownloadUrl").asText())).isEqualTo(200);

    String key = "raw/" + userId + "/" + sessionId + ".zip";
    assertThat(storage.sizeOf(key)).contains((long) zip.length);

    MvcResult del = api.call(HttpMethod.DELETE, "/v1/me", t, null);
    assertThat(del.getResponse().getStatus()).isEqualTo(202);

    // Rows are gone immediately; the account is unusable.
    assertThat(count("sessions", userId)).isZero();
    assertThat(count("devices", userId)).isZero();
    assertThat(count("consents", userId)).isZero();
    assertThat(count("refresh_tokens", userId)).isZero();
    assertThat(
            jdbc.sql("select count(*) from labels where session_id = :s")
                .param("s", sessionId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(api.call(HttpMethod.GET, "/v1/me", t, null).getResponse().getStatus())
        .isEqualTo(401);
    assertThat(api.login(email, Api.PASSWORD)).isNull();
    assertThat(
            api.call(HttpMethod.POST, "/v1/auth/refresh", null, Map.of("refreshToken", t.refresh()))
                .getResponse()
                .getStatus())
        .isEqualTo(401);

    // The raw object is removed asynchronously through the outbox.
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> storage.sizeOf(key).isEmpty());

    // The email can be registered again.
    assertThat(api.register(email)).isNotNull();
  }

  private long count(String table, UUID userId) {
    return jdbc.sql("select count(*) from " + table + " where user_id = :u")
        .param("u", userId)
        .query(Long.class)
        .single();
  }
}
