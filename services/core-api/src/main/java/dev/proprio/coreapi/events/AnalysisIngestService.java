package dev.proprio.coreapi.events;

import com.fasterxml.jackson.databind.JsonNode;
import dev.proprio.coreapi.analysis.PredictionRepository;
import dev.proprio.coreapi.session.SessionEntity;
import dev.proprio.coreapi.session.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a validated {@code session.analyzed} event. System context (no user): the session id
 * comes from our own event, never from a user request. Idempotent via {@code processed_events}.
 */
@Service
public class AnalysisIngestService {

  private static final Logger log = LoggerFactory.getLogger(AnalysisIngestService.class);

  private final JdbcClient jdbc;
  private final SessionRepository sessions;
  private final PredictionRepository predictions;
  private final Clock clock;

  public AnalysisIngestService(
      JdbcClient jdbc, SessionRepository sessions, PredictionRepository predictions, Clock clock) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.predictions = predictions;
    this.clock = clock;
  }

  /**
   * @return false when the event was a duplicate (already processed)
   */
  @Transactional
  public boolean apply(JsonNode event) {
    UUID eventId = UUID.fromString(event.get("event_id").asText());
    int inserted =
        jdbc.sql("insert into processed_events (event_id) values (:id) on conflict do nothing")
            .param("id", eventId)
            .update();
    if (inserted == 0) {
      log.info("Duplicate session.analyzed {} ignored", eventId);
      return false;
    }
    UUID sessionId = UUID.fromString(event.get("session_id").asText());
    SessionEntity session = sessions.findById(sessionId).orElse(null);
    if (session == null) {
      log.warn("session.analyzed {} for unknown session {} ignored", eventId, sessionId);
      return true;
    }
    Instant now = clock.instant();
    if ("ok".equals(event.get("status").asText())) {
      JsonNode model = event.get("model");
      String version = model.get("version").asText();
      String name = model.get("name").asText();
      String profile = event.get("profile_id").asText();
      String transform = event.get("transform_id").asText();
      for (JsonNode seg : event.get("segments")) {
        predictions.upsert(
            new PredictionRepository.Prediction(
                sessionId,
                seg.get("segment_id").asInt(),
                version,
                seg.get("kind").asText(),
                seg.get("start_s").asDouble(),
                seg.get("end_s").asDouble(),
                text(seg, "predicted_exercise"),
                text(seg, "predicted_kind"),
                seg.hasNonNull("confidence") ? seg.get("confidence").asDouble() : null,
                seg.hasNonNull("rep_count") ? seg.get("rep_count").asInt() : null,
                name,
                profile,
                transform),
            now);
      }
      session.markAnalyzed(now);
    } else if (!SessionEntity.ANALYZED.equals(session.getStatus())) {
      JsonNode error = event.get("error");
      session.markFailed(now, error.get("code").asText() + ": " + error.get("message").asText());
    } else {
      log.info("Late failure event {} for already analyzed session {} ignored", eventId, sessionId);
    }
    sessions.save(session);
    return true;
  }

  private static String text(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asText() : null;
  }
}
