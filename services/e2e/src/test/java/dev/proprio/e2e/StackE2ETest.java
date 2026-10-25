package dev.proprio.e2e;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Spec docs/specs/phase5-services.md section 6, E2E steps 1-10, against a running compose stack
 * (deploy/compose.yaml). One ordered scenario: each test builds on the state the previous one left.
 *
 * <p>The golden session's samples are synthetic, so the classifier mostly says "rest": this suite
 * asserts structure and the state machine, not exercise labels.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackE2ETest {
  static final Duration ANALYSIS_TIMEOUT = Duration.ofSeconds(60);
  static final String PASSWORD = "correct-horse-battery-staple";

  final Golden golden = Golden.load();
  final String suffix = UUID.randomUUID().toString().substring(0, 8);
  final String emailA = "e2e-a-" + suffix + "@example.com";
  final String emailB = "e2e-b-" + suffix + "@example.com";

  String accessA;
  String refreshA;
  String accessB;
  String sessionId;
  String labelsEtag;

  @BeforeAll
  void configure() {
    RestAssured.baseURI = System.getenv().getOrDefault("E2E_BASE_URL", "http://localhost:8080");
    sessionId = golden.sessionId();
  }

  static RequestSpecification api(String token) {
    RequestSpecification r = given().contentType(ContentType.JSON).accept(ContentType.JSON);
    return token == null ? r : r.auth().oauth2(token);
  }

  static Map<String, Object> credentials(String email) {
    return Map.of("email", email, "password", PASSWORD);
  }

  // ---------------------------------------------------------------- 1-2 register, login

  @Test
  @Order(1)
  void healthIsUp() {
    api(null).get("/health").then().statusCode(200);
  }

  @Test
  @Order(2)
  void registerReturnsTokens() {
    Response a =
        api(null)
            .body(Map.of("email", emailA, "password", PASSWORD, "participantId", "E2E_A"))
            .post("/v1/auth/register");
    assertEquals(201, a.statusCode(), a.asString());
    assertEquals("Bearer", a.path("tokenType"));
    assertNotNull(a.path("accessToken"));
    assertNotNull(a.path("refreshToken"));

    Response b = api(null).body(credentials(emailB)).post("/v1/auth/register");
    assertEquals(201, b.statusCode(), b.asString());
    accessB = b.path("accessToken");

    // registering the same email again conflicts (the contract's 409)
    api(null).body(credentials(emailA)).post("/v1/auth/register").then().statusCode(409);
  }

  @Test
  @Order(3)
  void loginReturnsTokensAndTheTokenWorks() {
    api(null).body(Map.of("email", emailA, "password", "wrong-password-123")).post("/v1/auth/login")
        .then()
        .statusCode(401);

    Response r = api(null).body(credentials(emailA)).post("/v1/auth/login");
    assertEquals(200, r.statusCode(), r.asString());
    accessA = r.path("accessToken");
    refreshA = r.path("refreshToken");
    assertTrue((Integer) r.path("expiresIn") > 0);

    api(accessA).get("/v1/me").then().statusCode(200).body("email", org.hamcrest.Matchers.equalTo(emailA));
    api(null).get("/v1/me").then().statusCode(401);
  }

  // ---------------------------------------------------------------- 3-5 create, PUT, complete

  @Test
  @Order(4)
  void createSessionReturnsAPresignedTicket() {
    JsonNode device = golden.meta.get("device");
    Map<String, Object> body = new HashMap<>();
    body.put("sessionId", sessionId);
    body.put("kind", "gym");
    body.put("sha256", golden.sha256);
    body.put("sizeBytes", golden.zip.length);
    body.put("rawFormat", 1);
    body.put("startedAt", golden.meta.get("started_at_utc").asText());
    body.put("durationS", golden.durationS());
    body.put(
        "device",
        Map.of(
            "manufacturer", device.get("manufacturer").asText(),
            "model", device.get("model").asText(),
            "sdkInt", device.get("sdk_int").asInt()));

    Response r = api(accessA).body(body).post("/v1/sessions");
    assertEquals(201, r.statusCode(), r.asString());
    assertEquals("PUT", r.path("uploadMethod"));
    assertEquals("CREATED", r.path("session.status"));
    assertEquals(sessionId, r.path("session.sessionId"));
    assertEquals("application/zip", r.path("uploadHeaders.Content-Type"));
    assertNotNull(r.path("expiresAt"));

    String url = r.path("uploadUrl");
    assertTrue(url.contains("X-Amz-Signature"), "presigned URL expected: " + url);

    // step 4: PUT the zip straight to object storage (the host in the URL must be reachable here)
    Map<String, String> headers = r.path("uploadHeaders");
    try {
      HttpRequest.Builder put =
          HttpRequest.newBuilder(URI.create(url))
              .PUT(HttpRequest.BodyPublishers.ofByteArray(golden.zip));
      headers.forEach(put::header);
      HttpResponse<String> res =
          HttpClient.newHttpClient().send(put.build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, res.statusCode(), res.body());
    } catch (Exception e) {
      throw new AssertionError("upload to " + url + " failed", e);
    }
  }

  @Test
  @Order(5)
  void completeQueuesTheAnalysis() {
    Response r = api(accessA).post("/v1/sessions/{id}/complete", sessionId);
    assertEquals(202, r.statusCode(), r.asString());
    String status = r.path("status");
    assertTrue(Set.of("UPLOADED", "ANALYZING", "ANALYZED").contains(status), status);

    // completing again is a conflict or an idempotent accept, never a server error
    int again = api(accessA).post("/v1/sessions/{id}/complete", sessionId).statusCode();
    assertTrue(again == 202 || again == 409, "second complete: " + again);
  }

  // ---------------------------------------------------------------- 6-7 analysis

  @Test
  @Order(6)
  void analysisReachesAnalyzedWithTheGoldenSegments() throws Exception {
    JsonNode analysis = pollAnalysis(accessA, sessionId);

    assertEquals(sessionId, analysis.get("sessionId").asText());
    JsonNode model = analysis.get("model");
    assertNotNull(model, "model {name, version}");
    assertFalse(model.get("name").asText().isBlank());
    assertFalse(model.get("version").asText().isBlank());
    assertFalse(analysis.get("profileId").asText().isBlank());
    assertFalse(analysis.get("transformId").asText().isBlank());

    Map<Integer, JsonNode> byId = new HashMap<>();
    for (JsonNode s : analysis.get("segments")) byId.put(s.get("segmentId").asInt(), s);

    int checked = 0;
    for (JsonNode exp : golden.expected.get("segments")) {
      int id = exp.get("segment_id").asInt();
      if (id < 0) continue; // implicit rest gaps between marks are the analyzer's business
      checked++;
      JsonNode seg = byId.get(id);
      assertNotNull(seg, "segment " + id + " missing from analysis " + byId.keySet());
      assertEquals(exp.get("kind").asText(), seg.get("kind").asText(), "kind of segment " + id);
      assertEquals(
          golden.relativeS(exp.get("t_start_ns").asLong()), seg.get("startS").asDouble(), 0.05);
      assertEquals(
          golden.relativeS(exp.get("t_end_ns").asLong()), seg.get("endS").asDouble(), 0.05);
      assertTrue(seg.get("endS").asDouble() > seg.get("startS").asDouble());

      if (seg.get("kind").asText().equals("set")) {
        String pk = seg.path("predictedKind").asText();
        assertTrue(Set.of("set", "rest").contains(pk), "predictedKind of " + id + ": " + pk);
        double conf = seg.path("confidence").asDouble(-1);
        assertTrue(conf >= 0 && conf <= 1, "confidence of " + id + ": " + conf);
        if (pk.equals("set")) {
          assertTrue(
              Set.of("bench", "squat", "dead", "ohp", "row")
                  .contains(seg.path("predictedExercise").asText()));
          assertTrue(seg.path("repCount").asInt(-1) >= 0, "repCount of " + id);
        }
      }
    }
    assertEquals(5, checked, "golden has five marked segments");
    // the user has not labelled anything yet
    for (JsonNode s : analysis.get("segments")) {
      assertTrue(s.path("userExercise").isMissingNode() || s.get("userExercise").isNull());
    }

    JsonNode session = Golden.JSON.readTree(api(accessA).get("/v1/sessions/{id}", sessionId).asString());
    assertEquals("ANALYZED", session.get("status").asText());
    assertNotNull(session.get("analyzedAt"));
    assertEquals(golden.meta.at("/device/model").asText(), session.get("deviceModel").asText());
  }

  @Test
  @Order(7)
  void labelsAreStoredWithETagConcurrencyAndShowUpInWorkouts() throws Exception {
    // labels.json from the golden zip, with segment 1 relabelled so we can tell user from model
    ObjectNode labels = golden.labels.deepCopy();
    ObjectNode seg1 = (ObjectNode) labels.get("segments").get(0);
    seg1.put("exercise", "row");
    seg1.put("reps_user", 7);
    seg1.put("load_kg", 55.0);

    Response put = api(accessA).body(labels.toString()).put("/v1/sessions/{id}/labels", sessionId);
    assertEquals(200, put.statusCode(), put.asString());
    String etag1 = put.header("ETag");
    assertNotNull(etag1, "ETag header");

    // a stale If-Match is rejected, the current one accepted
    api(accessA)
        .header("If-Match", "\"stale-0\"")
        .body(labels.toString())
        .put("/v1/sessions/{id}/labels", sessionId)
        .then()
        .statusCode(412);
    seg1.put("note", "second revision");
    Response put2 =
        api(accessA)
            .header("If-Match", etag1)
            .body(labels.toString())
            .put("/v1/sessions/{id}/labels", sessionId);
    assertEquals(200, put2.statusCode(), put2.asString());
    labelsEtag = put2.header("ETag");
    assertNotEquals(etag1, labelsEtag);

    // the body's session_id must match the path
    ObjectNode wrong = labels.deepCopy();
    wrong.put("session_id", UUID.randomUUID().toString());
    api(accessA).body(wrong.toString()).put("/v1/sessions/{id}/labels", sessionId)
        .then()
        .statusCode(org.hamcrest.Matchers.anyOf(
            org.hamcrest.Matchers.equalTo(400), org.hamcrest.Matchers.equalTo(422)));
    // a schema-invalid document is a 400
    api(accessA)
        .body("{\"labels_format\":1,\"session_id\":\"" + sessionId + "\",\"segments\":\"nope\"}")
        .put("/v1/sessions/{id}/labels", sessionId)
        .then()
        .statusCode(400);

    Response get = api(accessA).get("/v1/sessions/{id}/labels", sessionId);
    assertEquals(200, get.statusCode());
    assertEquals(labelsEtag, get.header("ETag"));
    assertEquals("row", get.path("segments[0].exercise"));

    // analysis merges the user's labels
    JsonNode analysis = Golden.JSON.readTree(api(accessA).get("/v1/sessions/{id}/analysis", sessionId).asString());
    JsonNode s1 = null;
    for (JsonNode s : analysis.get("segments")) if (s.get("segmentId").asInt() == 1) s1 = s;
    assertNotNull(s1);
    assertEquals("row", s1.get("userExercise").asText());
    assertEquals(7, s1.get("userReps").asInt());

    // history: the user's labels are the truth (source "user")
    Response workouts =
        api(accessA)
            .queryParam("from", "2025-06-01")
            .queryParam("to", "2025-06-30")
            .get("/v1/workouts");
    assertEquals(200, workouts.statusCode(), workouts.asString());
    List<Map<String, Object>> sets = workouts.path("$");
    Map<Integer, Map<String, Object>> byId = new HashMap<>();
    for (Map<String, Object> s : sets) {
      assertEquals(sessionId, s.get("sessionId"));
      byId.put((Integer) s.get("segmentId"), s);
    }
    // labelled lifts 1, 2, 4, 5 are logged sets; 3 is "other"
    assertEquals(Set.of(1, 2, 4, 5), new HashSet<>(byId.keySet()), "logged sets: " + sets);
    assertEquals("row", byId.get(1).get("exercise"));
    assertEquals(7, byId.get(1).get("reps"));
    assertEquals("user", byId.get(1).get("source"));
    assertEquals("squat", byId.get(2).get("exercise"));
    assertEquals("dead", byId.get(4).get("exercise"));
    assertEquals("ohp", byId.get(5).get("exercise"));

    // the exercise filter and an empty range
    List<Map<String, Object>> rows =
        api(accessA)
            .queryParam("from", "2025-06-01")
            .queryParam("to", "2025-06-30")
            .queryParam("exercise", "squat")
            .get("/v1/workouts")
            .path("$");
    assertEquals(1, rows.size());
    List<?> none =
        api(accessA).queryParam("from", "2020-01-01").queryParam("to", "2020-01-31")
            .get("/v1/workouts").path("$");
    assertTrue(none.isEmpty());

    // the session shows up in the list
    Response list = api(accessA).get("/v1/sessions");
    assertEquals(200, list.statusCode());
    List<String> ids = list.path("items.sessionId");
    assertTrue(ids.contains(sessionId));
  }

  // ---------------------------------------------------------------- 8 refresh rotation

  @Test
  @Order(8)
  void refreshTokenRotatesAndReuseRevokesTheFamily() {
    Response first = api(null).body(Map.of("refreshToken", refreshA)).post("/v1/auth/refresh");
    assertEquals(200, first.statusCode(), first.asString());
    String refresh2 = first.path("refreshToken");
    String access2 = first.path("accessToken");
    assertNotEquals(refreshA, refresh2, "refresh token must rotate");
    api(access2).get("/v1/me").then().statusCode(200);

    // the old token is rejected...
    api(null).body(Map.of("refreshToken", refreshA)).post("/v1/auth/refresh").then().statusCode(401);
    // ...and presenting it counts as theft: the whole family, including the new token, is revoked
    api(null).body(Map.of("refreshToken", refresh2)).post("/v1/auth/refresh").then().statusCode(401);

    // a fresh login starts a new family
    Response login = api(null).body(credentials(emailA)).post("/v1/auth/login");
    assertEquals(200, login.statusCode());
    accessA = login.path("accessToken");
    refreshA = login.path("refreshToken");
  }

  // ---------------------------------------------------------------- 9 cross-user 404

  @Test
  @Order(9)
  void anotherUsersSessionIsNotFound() {
    api(accessB).get("/v1/sessions/{id}", sessionId).then().statusCode(404);
    api(accessB).get("/v1/sessions/{id}/analysis", sessionId).then().statusCode(404);
    api(accessB).get("/v1/sessions/{id}/labels", sessionId).then().statusCode(404);
    api(accessB).body(golden.labels.toString()).put("/v1/sessions/{id}/labels", sessionId)
        .then()
        .statusCode(404);
    api(accessB).post("/v1/sessions/{id}/complete", sessionId).then().statusCode(404);

    // B sees nothing of A's, in the list or in history
    List<String> ids = api(accessB).get("/v1/sessions").path("items.sessionId");
    assertTrue(ids.isEmpty(), "B's sessions: " + ids);
    List<?> sets =
        api(accessB).queryParam("from", "2025-06-01").queryParam("to", "2025-06-30")
            .get("/v1/workouts").path("$");
    assertTrue(sets.isEmpty());

    // and A's data is untouched by B's attempts
    api(accessA).get("/v1/sessions/{id}/labels", sessionId).then().statusCode(200);
  }

  // ---------------------------------------------------------------- 10 delete account

  @Test
  @Order(10)
  void deletingTheAccountRemovesItsData() {
    Response del = api(accessA).delete("/v1/me");
    assertEquals(202, del.statusCode(), del.asString());

    // credentials no longer work, and neither does a refresh token from before
    api(null).body(credentials(emailA)).post("/v1/auth/login").then().statusCode(401);
    api(null).body(Map.of("refreshToken", refreshA)).post("/v1/auth/refresh").then().statusCode(401);

    // the unexpired access token must not reach the deleted data
    int status = api(accessA).get("/v1/sessions/{id}", sessionId).statusCode();
    assertTrue(status == 401 || status == 404, "deleted user's session: " + status);

    // B is unaffected
    api(accessB).get("/v1/me").then().statusCode(200);
    api(accessB).delete("/v1/me").then().statusCode(202);
  }

  // ---------------------------------------------------------------- helpers

  /** Polls the analysis until ANALYZED; fails fast and clearly on FAILED or on timeout. */
  JsonNode pollAnalysis(String token, String id) throws Exception {
    Instant deadline = Instant.now().plus(ANALYSIS_TIMEOUT);
    String last = "?";
    while (Instant.now().isBefore(deadline)) {
      Response r = api(token).get("/v1/sessions/{id}/analysis", id);
      assertEquals(200, r.statusCode(), r.asString());
      last = r.path("status");
      if (last.equals("ANALYZED")) return Golden.JSON.readTree(r.asString());
      if (last.equals("FAILED")) {
        String reason = api(token).get("/v1/sessions/{id}", id).path("failureReason");
        fail("analysis FAILED: " + reason);
      }
      Thread.sleep(500);
    }
    return fail("analysis not ANALYZED within " + ANALYSIS_TIMEOUT.toSeconds() + " s (last status " + last + ")");
  }
}
