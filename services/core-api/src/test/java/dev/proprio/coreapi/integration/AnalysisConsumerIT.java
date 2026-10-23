package dev.proprio.coreapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proprio.coreapi.analysis.PredictionRepository;
import dev.proprio.coreapi.support.Api;
import dev.proprio.coreapi.support.Golden;
import dev.proprio.coreapi.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;

class AnalysisConsumerIT extends IntegrationTest {

  @Autowired RabbitTemplate rabbit;
  @Autowired JdbcClient jdbc;
  @Autowired PredictionRepository predictions;

  @Value("${proprio.messaging.exchange}")
  String exchange;

  private void publish(ObjectNode event) {
    publishRaw(event.toString());
  }

  private void publishRaw(String json) {
    MessageProperties p = new MessageProperties();
    p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    Message m =
        MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8)).andProperties(p).build();
    rabbit.send(exchange, "session.analyzed", m);
  }

  private JsonNode session(Api.Tokens t, UUID id) throws Exception {
    return api.json(api.call(HttpMethod.GET, "/v1/sessions/" + id, t, null));
  }

  private void awaitStatus(Api.Tokens t, UUID id, String status) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> status.equals(session(t, id).get("status").asText()));
  }

  private UUID newSession(Api.Tokens t) throws Exception {
    UUID id = UUID.randomUUID();
    assertThat(api.createSession(t, id, Golden.gymZip()).getResponse().getStatus()).isEqualTo(201);
    return id;
  }

  private long processed(UUID eventId) {
    return jdbc.sql("select count(*) from processed_events where event_id = :id")
        .param("id", eventId)
        .query(Long.class)
        .single();
  }

  @Test
  void analyzedEventUpdatesStatusAndPredictionsIdempotently() throws Exception {
    Api.Tokens t = api.register();
    UUID sessionId = newSession(t);
    UUID eventId = UUID.randomUUID();
    ObjectNode event = api.analyzedOk(eventId, sessionId, "v1");

    publish(event);
    awaitStatus(t, sessionId, "ANALYZED");

    JsonNode analysis =
        api.json(api.call(HttpMethod.GET, "/v1/sessions/" + sessionId + "/analysis", t, null));
    assertThat(analysis.get("status").asText()).isEqualTo("ANALYZED");
    assertThat(analysis.get("model").get("version").asText()).isEqualTo("v1");
    assertThat(analysis.get("model").get("name").asText()).isEqualTo("proprio-classifier");
    assertThat(analysis.get("profileId").asText()).isEqualTo("galaxy_watch_v1");
    assertThat(analysis.get("segments")).hasSize(3);
    JsonNode s1 = analysis.get("segments").get(0);
    // ordered by segment id: -1 (rest), 1, 2
    assertThat(s1.get("segmentId").asInt()).isEqualTo(-1);
    assertThat(s1.get("kind").asText()).isEqualTo("rest");
    JsonNode set1 = analysis.get("segments").get(1);
    assertThat(set1.get("predictedExercise").asText()).isEqualTo("squat");
    assertThat(set1.get("repCount").asInt()).isEqualTo(6);
    assertThat(set1.get("confidence").asDouble()).isEqualTo(0.8);
    assertThat(session(t, sessionId).has("analyzedAt")).isTrue();
    assertThat(processed(eventId)).isEqualTo(1);
    assertThat(predictions.count(sessionId)).isEqualTo(3);

    // Redelivery of the same event: no duplicates, state unchanged.
    publish(event);
    // A second, distinct event that the consumer must still process proves the first redelivery
    // has been consumed already (single consumer, FIFO).
    UUID marker = UUID.randomUUID();
    UUID otherSession = newSession(t);
    publish(api.analyzedOk(marker, otherSession, "v1"));
    awaitStatus(t, otherSession, "ANALYZED");

    assertThat(processed(eventId)).isEqualTo(1);
    assertThat(predictions.count(sessionId)).isEqualTo(3);
    JsonNode after =
        api.json(api.call(HttpMethod.GET, "/v1/sessions/" + sessionId + "/analysis", t, null));
    assertThat(after).isEqualTo(analysis);
  }

  @Test
  void sameResultsUnderANewEventIdDoNotDuplicateSegments() throws Exception {
    Api.Tokens t = api.register();
    UUID sessionId = newSession(t);
    publish(api.analyzedOk(UUID.randomUUID(), sessionId, "v1"));
    awaitStatus(t, sessionId, "ANALYZED");

    UUID second = UUID.randomUUID();
    publish(api.analyzedOk(second, sessionId, "v1")); // inference re-published with a new event id
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> processed(second) == 1);
    assertThat(predictions.count(sessionId)).isEqualTo(3);
  }

  @Test
  void newerModelVersionBecomesTheLatestAnalysis() throws Exception {
    Api.Tokens t = api.register();
    UUID sessionId = newSession(t);
    publish(api.analyzedOk(UUID.randomUUID(), sessionId, "v1"));
    awaitStatus(t, sessionId, "ANALYZED");

    ObjectNode v2 = api.analyzedOk(UUID.randomUUID(), sessionId, "v2");
    ((ObjectNode) v2.get("segments").get(0)).put("predicted_exercise", "dead");
    publish(v2);
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> predictions.count(sessionId) == 6);

    JsonNode analysis =
        api.json(api.call(HttpMethod.GET, "/v1/sessions/" + sessionId + "/analysis", t, null));
    assertThat(analysis.get("model").get("version").asText()).isEqualTo("v2");
    assertThat(analysis.get("segments")).hasSize(3);
    assertThat(analysis.get("segments").get(1).get("predictedExercise").asText()).isEqualTo("dead");
  }

  @Test
  void failedEventMarksTheSessionFailedWithReason() throws Exception {
    Api.Tokens t = api.register();
    UUID sessionId = newSession(t);
    ObjectNode failed = mapper.createObjectNode();
    failed.put("schema_version", 1);
    failed.put("event_id", UUID.randomUUID().toString());
    failed.put("occurred_at", "2026-01-01T00:00:00Z");
    failed.put("caused_by", UUID.randomUUID().toString());
    failed.put("session_id", sessionId.toString());
    failed.put("status", "failed");
    failed
        .putObject("error")
        .put("code", "hash_mismatch")
        .put("message", "sha256 differs")
        .put("retryable", false);
    publish(failed);

    awaitStatus(t, sessionId, "FAILED");
    assertThat(session(t, sessionId).get("failureReason").asText()).contains("hash_mismatch");
  }

  @Test
  void poisonMessagesGoToTheDeadLetterQueue() throws Exception {
    publishRaw("this is not json");
    ObjectNode invalid = api.analyzedOk(UUID.randomUUID(), UUID.randomUUID(), "v1");
    invalid.put("status", "maybe");
    publish(invalid);

    int[] seen = {0};
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () -> {
              while (rabbit.receive("core.session-analyzed.dlq", 200) != null) {
                seen[0]++;
              }
              return seen[0] >= 2;
            });
    // and the consumer keeps working afterwards
    Api.Tokens t = api.register();
    UUID sessionId = newSession(t);
    publish(api.analyzedOk(UUID.randomUUID(), sessionId, "v1"));
    awaitStatus(t, sessionId, "ANALYZED");
  }

  @Test
  void eventsForUnknownSessionsAreIgnored() throws Exception {
    UUID eventId = UUID.randomUUID();
    publish(api.analyzedOk(eventId, UUID.randomUUID(), "v1"));
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> processed(eventId) == 1);
  }

  @Test
  void workoutsMergeUserLabelsOverPredictions() throws Exception {
    Api.Tokens t = api.register();
    UUID sessionId = Golden.gymSessionId();
    assertThat(api.createSession(t, sessionId, Golden.gymZip()).getResponse().getStatus())
        .isEqualTo(201);

    // Predictions only: model source, reps from the counter.
    publish(api.analyzedOk(UUID.randomUUID(), sessionId, "v1"));
    awaitStatus(t, sessionId, "ANALYZED");
    JsonNode modelOnly =
        api.json(api.call(HttpMethod.GET, "/v1/workouts?from=2025-06-15&to=2025-06-15", t, null));
    assertThat(modelOnly).hasSize(2);
    assertThat(modelOnly.get(0).get("source").asText()).isEqualTo("model");
    assertThat(modelOnly.get(0).get("exercise").asText()).isEqualTo("squat");
    assertThat(modelOnly.get(0).get("reps").asInt()).isEqualTo(6);
    assertThat(modelOnly.get(0).get("date").asText())
        .startsWith("2025-06-15T15:06:50"); // start + 10 s

    // Now the user labels: segment 1 is a bench set of 5 reps at 60 kg; segment 2 is not a set.
    ObjectNode labels = (ObjectNode) Golden.gymLabels().deepCopy();
    var segs = labels.putArray("segments");
    segs.addObject()
        .put("segment_id", 1)
        .put("kind", "set")
        .put("exercise", "bench")
        .put("load_kg", 60.0)
        .put("reps_user", 5)
        .put("rpe", 8);
    segs.addObject().put("segment_id", 2).put("kind", "other");
    assertThat(
            api.call(HttpMethod.PUT, "/v1/sessions/" + sessionId + "/labels", t, labels.toString())
                .getResponse()
                .getStatus())
        .isEqualTo(200);

    JsonNode merged =
        api.json(api.call(HttpMethod.GET, "/v1/workouts?from=2025-06-15&to=2025-06-15", t, null));
    assertThat(merged).hasSize(1);
    JsonNode set = merged.get(0);
    assertThat(set.get("source").asText()).isEqualTo("user");
    assertThat(set.get("exercise").asText()).isEqualTo("bench");
    assertThat(set.get("reps").asInt()).isEqualTo(5);
    assertThat(set.get("loadKg").asDouble()).isEqualTo(60.0);
    assertThat(set.get("rpe").asDouble()).isEqualTo(8.0);

    // Filters and ranges.
    assertThat(
            api.json(
                api.call(
                    HttpMethod.GET,
                    "/v1/workouts?from=2025-06-15&to=2025-06-15&exercise=squat",
                    t,
                    null)))
        .isEmpty();
    assertThat(
            api.json(
                api.call(HttpMethod.GET, "/v1/workouts?from=2025-06-16&to=2025-06-20", t, null)))
        .isEmpty();
    assertThat(
            api.call(HttpMethod.GET, "/v1/workouts?from=2025-06-20&to=2025-06-15", t, null)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            api.call(HttpMethod.GET, "/v1/workouts?from=2025-06-15", t, null)
                .getResponse()
                .getStatus())
        .isEqualTo(400);

    // The analysis view carries the user's truth next to the prediction.
    JsonNode analysis =
        api.json(api.call(HttpMethod.GET, "/v1/sessions/" + sessionId + "/analysis", t, null));
    JsonNode seg1 = analysis.get("segments").get(1);
    assertThat(seg1.get("predictedExercise").asText()).isEqualTo("squat");
    assertThat(seg1.get("userExercise").asText()).isEqualTo("bench");
    assertThat(seg1.get("userReps").asInt()).isEqualTo(5);
    assertThat(seg1.get("loadKg").asDouble()).isEqualTo(60.0);
  }
}
