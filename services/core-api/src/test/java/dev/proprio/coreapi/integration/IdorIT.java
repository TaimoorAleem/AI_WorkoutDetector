package dev.proprio.coreapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.proprio.coreapi.support.Api;
import dev.proprio.coreapi.support.Golden;
import dev.proprio.coreapi.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

/** User B must get 404 (never 403, never data) for everything that belongs to user A. */
class IdorIT extends IntegrationTest {

  @Test
  void userBCannotReachUserAsSessionLabelsCompleteOrAnalysis() throws Exception {
    Api.Tokens a = api.register();
    Api.Tokens b = api.register();
    UUID sessionId = UUID.randomUUID();
    byte[] zip = Golden.gymZip();

    MvcResult created = api.createSession(a, sessionId, zip);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    String url = "/v1/sessions/" + sessionId;

    // A owns it and can read it.
    assertThat(api.call(HttpMethod.GET, url, a, null).getResponse().getStatus()).isEqualTo(200);

    // Session
    assertNotFound(api.call(HttpMethod.GET, url, b, null));
    // Complete
    assertNotFound(api.call(HttpMethod.POST, url + "/complete", b, null));
    // Labels (read and write)
    assertNotFound(api.call(HttpMethod.GET, url + "/labels", b, null));
    JsonNode labels = Golden.gymLabels().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) labels)
        .put("session_id", sessionId.toString());
    assertNotFound(api.call(HttpMethod.PUT, url + "/labels", b, labels.toString()));
    // Analysis
    assertNotFound(api.call(HttpMethod.GET, url + "/analysis", b, null));

    // B's failed attempts changed nothing for A.
    JsonNode still = api.json(api.call(HttpMethod.GET, url, a, null));
    assertThat(still.get("status").asText()).isEqualTo("CREATED");
    assertThat(api.call(HttpMethod.GET, url + "/labels", a, null).getResponse().getStatus())
        .isEqualTo(404); // never written by B (this 404 is "no labels yet", for the owner)
  }

  @Test
  void foreignSessionsDoNotAppearInListsHistoryOrExport() throws Exception {
    Api.Tokens a = api.register();
    Api.Tokens b = api.register();
    UUID sessionId = UUID.randomUUID();
    api.createSession(a, sessionId, Golden.gymZip());

    JsonNode bList = api.json(api.call(HttpMethod.GET, "/v1/sessions", b, null));
    assertThat(bList.get("items")).isEmpty();
    JsonNode bWorkouts =
        api.json(api.call(HttpMethod.GET, "/v1/workouts?from=2025-06-01&to=2025-06-30", b, null));
    assertThat(bWorkouts).isEmpty();
    JsonNode bExport = api.json(api.call(HttpMethod.GET, "/v1/me/export", b, null));
    assertThat(bExport.get("sessions")).isEmpty();
  }

  @Test
  void claimingAnotherUsersSessionIdIsAConflictAndLeavesTheOriginalIntact() throws Exception {
    Api.Tokens a = api.register();
    Api.Tokens b = api.register();
    UUID sessionId = UUID.randomUUID();
    api.createSession(a, sessionId, Golden.gymZip());

    MvcResult r = api.createSession(b, sessionId, Golden.gymZip());
    assertThat(r.getResponse().getStatus()).isEqualTo(409);
    assertThat(
            api.call(HttpMethod.GET, "/v1/sessions/" + sessionId, a, null)
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertNotFound(api.call(HttpMethod.GET, "/v1/sessions/" + sessionId, b, null));
  }

  @Test
  void unknownIdAndForeignIdAreIndistinguishable() throws Exception {
    Api.Tokens a = api.register();
    Api.Tokens b = api.register();
    UUID sessionId = UUID.randomUUID();
    api.createSession(a, sessionId, Golden.gymZip());

    MvcResult foreign = api.call(HttpMethod.GET, "/v1/sessions/" + sessionId, b, null);
    MvcResult missing = api.call(HttpMethod.GET, "/v1/sessions/" + UUID.randomUUID(), b, null);
    assertThat(foreign.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus());
    assertThat(api.json(foreign).get("detail")).isEqualTo(api.json(missing).get("detail"));
  }

  private void assertNotFound(MvcResult r) throws Exception {
    assertThat(r.getResponse().getStatus()).isEqualTo(404);
    assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
  }
}
