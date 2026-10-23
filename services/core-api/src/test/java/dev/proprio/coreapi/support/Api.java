package dev.proprio.coreapi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Thin test client over MockMvc for the core-api REST contract. */
public class Api {

  public static final String PASSWORD = "correct-horse-battery-staple";

  public record Tokens(String access, String refresh) {}

  private final MockMvc mvc;
  private final ObjectMapper mapper;

  public Api(MockMvc mvc, ObjectMapper mapper) {
    this.mvc = mvc;
    this.mapper = mapper;
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  public static String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.test";
  }

  public MvcResult call(
      HttpMethod method, String path, Tokens as, Object body, String... headerPairs)
      throws Exception {
    MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, path);
    if (as != null) {
      b.header("Authorization", "Bearer " + as.access());
    }
    for (int i = 0; i + 1 < headerPairs.length; i += 2) {
      b.header(headerPairs[i], headerPairs[i + 1]);
    }
    if (body != null) {
      b.contentType(MediaType.APPLICATION_JSON)
          .content(body instanceof String s ? s : mapper.writeValueAsString(body));
    }
    return mvc.perform(b).andReturn();
  }

  public JsonNode json(MvcResult r) throws Exception {
    String content = r.getResponse().getContentAsString();
    return content.isEmpty() ? null : mapper.readTree(content);
  }

  public Tokens register(String email) throws Exception {
    MvcResult r = call(HttpMethod.POST, "/v1/auth/register", null, new Creds(email, PASSWORD));
    if (r.getResponse().getStatus() != 201) {
      throw new AssertionError("register failed: " + r.getResponse().getContentAsString());
    }
    return tokens(json(r));
  }

  public Tokens register() throws Exception {
    return register(uniqueEmail());
  }

  public Tokens login(String email, String password) throws Exception {
    MvcResult r = call(HttpMethod.POST, "/v1/auth/login", null, new Creds(email, password));
    return r.getResponse().getStatus() == 200 ? tokens(json(r)) : null;
  }

  public static Tokens tokens(JsonNode body) {
    return new Tokens(body.get("accessToken").asText(), body.get("refreshToken").asText());
  }

  public UUID userId(Tokens t) throws Exception {
    return UUID.fromString(json(call(HttpMethod.GET, "/v1/me", t, null)).get("id").asText());
  }

  public ObjectNode createSessionBody(UUID sessionId, byte[] zip) {
    ObjectNode body = mapper.createObjectNode();
    body.put("sessionId", sessionId.toString());
    body.put("kind", "gym");
    body.put("sha256", Golden.sha256(zip));
    body.put("sizeBytes", zip.length);
    body.put("rawFormat", 1);
    body.put("startedAt", "2025-06-15T15:06:40Z");
    body.put("durationS", 225.0);
    body.putObject("device")
        .put("manufacturer", "samsung")
        .put("model", "SM-R960")
        .put("sdkInt", 34);
    return body;
  }

  /** POST /v1/sessions; returns the raw result so tests can inspect status codes. */
  public MvcResult createSession(Tokens t, UUID sessionId, byte[] zip) throws Exception {
    return call(HttpMethod.POST, "/v1/sessions", t, createSessionBody(sessionId, zip));
  }

  /** PUTs {@code zip} to the presigned URL of an upload ticket; returns the HTTP status. */
  public static int putToStorage(JsonNode ticket, byte[] zip, boolean withContentType)
      throws Exception {
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(ticket.get("uploadUrl").asText()))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(zip));
    if (withContentType) {
      ticket
          .get("uploadHeaders")
          .fields()
          .forEachRemaining(e -> req.header(e.getKey(), e.getValue().asText()));
    }
    return HttpClient.newHttpClient()
        .send(req.build(), HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  public static int get(String url) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  /** A valid session.analyzed event with one set segment and one rest segment. */
  public ObjectNode analyzedOk(UUID eventId, UUID sessionId, String modelVersion) {
    ObjectNode e = mapper.createObjectNode();
    e.put("schema_version", 1);
    e.put("event_id", eventId.toString());
    e.put("occurred_at", Instant.now().toString());
    e.put("caused_by", UUID.randomUUID().toString());
    e.put("session_id", sessionId.toString());
    e.put("status", "ok");
    e.putObject("model").put("name", "proprio-classifier").put("version", modelVersion);
    e.put("profile_id", "galaxy_watch_v1");
    e.put("transform_id", "csf1-test");
    var segs = e.putArray("segments");
    segs.addObject()
        .put("segment_id", 1)
        .put("kind", "set")
        .put("start_s", 10.0)
        .put("end_s", 40.0)
        .put("predicted_exercise", "squat")
        .put("predicted_kind", "set")
        .put("confidence", 0.8)
        .put("rep_count", 6);
    segs.addObject()
        .put("segment_id", -1)
        .put("kind", "rest")
        .put("start_s", 42.0)
        .put("end_s", 53.0)
        .put("predicted_kind", "rest");
    segs.addObject()
        .put("segment_id", 2)
        .put("kind", "set")
        .put("start_s", 60.0)
        .put("end_s", 90.0)
        .put("predicted_exercise", "bench")
        .put("predicted_kind", "set")
        .put("confidence", 0.9)
        .putNull("rep_count");
    return e;
  }

  public record Creds(String email, String password) {}
}
