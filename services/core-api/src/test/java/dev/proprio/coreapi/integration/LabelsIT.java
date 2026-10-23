package dev.proprio.coreapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proprio.coreapi.support.Api;
import dev.proprio.coreapi.support.Golden;
import dev.proprio.coreapi.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

class LabelsIT extends IntegrationTest {

  private UUID session(Api.Tokens t) throws Exception {
    UUID id = UUID.randomUUID();
    assertThat(api.createSession(t, id, Golden.gymZip()).getResponse().getStatus()).isEqualTo(201);
    return id;
  }

  private ObjectNode labelsFor(UUID id) {
    ObjectNode doc = (ObjectNode) Golden.gymLabels().deepCopy();
    doc.put("session_id", id.toString());
    return doc;
  }

  @Test
  void putStoresReturnsEtagAndGetRoundTrips() throws Exception {
    Api.Tokens t = api.register();
    UUID id = session(t);
    String path = "/v1/sessions/" + id + "/labels";

    assertThat(api.call(HttpMethod.GET, path, t, null).getResponse().getStatus()).isEqualTo(404);

    MvcResult put = api.call(HttpMethod.PUT, path, t, labelsFor(id).toString());
    assertThat(put.getResponse().getStatus()).isEqualTo(200);
    assertThat(put.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

    MvcResult get = api.call(HttpMethod.GET, path, t, null);
    assertThat(get.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    JsonNode doc = api.json(get);
    assertThat(doc).isEqualTo(labelsFor(id));
  }

  @Test
  void ifMatchOptimisticConcurrency() throws Exception {
    Api.Tokens t = api.register();
    UUID id = session(t);
    String path = "/v1/sessions/" + id + "/labels";
    api.call(HttpMethod.PUT, path, t, labelsFor(id).toString());

    ObjectNode edited = labelsFor(id);
    ((ObjectNode) edited.get("segments").get(0)).put("reps_user", 6);

    // Matching ETag -> accepted, version bumps.
    MvcResult ok = api.call(HttpMethod.PUT, path, t, edited.toString(), "If-Match", "\"1\"");
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(ok.getResponse().getHeader("ETag")).isEqualTo("\"2\"");
    assertThat(api.json(ok).get("segments").get(0).get("reps_user").asInt()).isEqualTo(6);

    // Stale ETag -> 412 problem, nothing changed.
    ObjectNode stale = labelsFor(id);
    ((ObjectNode) stale.get("segments").get(0)).put("reps_user", 9);
    MvcResult conflict = api.call(HttpMethod.PUT, path, t, stale.toString(), "If-Match", "\"1\"");
    assertThat(conflict.getResponse().getStatus()).isEqualTo(412);
    assertThat(conflict.getResponse().getContentType()).startsWith("application/problem+json");
    assertThat(
            api.json(api.call(HttpMethod.GET, path, t, null))
                .get("segments")
                .get(0)
                .get("reps_user")
                .asInt())
        .isEqualTo(6);

    // Wildcard and weak forms work; no If-Match is an unconditional replace.
    assertThat(
            api.call(HttpMethod.PUT, path, t, edited.toString(), "If-Match", "*")
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(
            api.call(HttpMethod.PUT, path, t, edited.toString(), "If-Match", "W/\"3\"")
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(api.call(HttpMethod.PUT, path, t, edited.toString()).getResponse().getHeader("ETag"))
        .isEqualTo("\"5\"");
  }

  @Test
  void ifMatchOnAMissingDocumentIs412() throws Exception {
    Api.Tokens t = api.register();
    UUID id = session(t);
    MvcResult r =
        api.call(
            HttpMethod.PUT,
            "/v1/sessions/" + id + "/labels",
            t,
            labelsFor(id).toString(),
            "If-Match",
            "\"1\"");
    assertThat(r.getResponse().getStatus()).isEqualTo(412);
  }

  @Test
  void documentsAreValidatedAgainstTheLabelsSchema() throws Exception {
    Api.Tokens t = api.register();
    UUID id = session(t);
    String path = "/v1/sessions/" + id + "/labels";

    ObjectNode bad = labelsFor(id);
    bad.remove("participant_id");
    ((ObjectNode) bad.get("segments").get(0)).put("exercise", "curl").put("rpe", 11);
    MvcResult r = api.call(HttpMethod.PUT, path, t, bad.toString());
    assertThat(r.getResponse().getStatus()).isEqualTo(400);
    assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
    JsonNode errors = api.json(r).get("errors");
    assertThat(errors.findValuesAsText("field"))
        .contains("$.segments[0].exercise", "$.segments[0].rpe");
    assertThat(api.call(HttpMethod.GET, path, t, null).getResponse().getStatus())
        .isEqualTo(404); // nothing stored
  }

  @Test
  void sessionIdInTheBodyMustMatchThePath() throws Exception {
    Api.Tokens t = api.register();
    UUID id = session(t);
    ObjectNode other = labelsFor(UUID.randomUUID());
    MvcResult r = api.call(HttpMethod.PUT, "/v1/sessions/" + id + "/labels", t, other.toString());
    assertThat(r.getResponse().getStatus()).isEqualTo(400);
    assertThat(api.json(r).get("errors").findValuesAsText("field")).contains("$.session_id");
  }
}
