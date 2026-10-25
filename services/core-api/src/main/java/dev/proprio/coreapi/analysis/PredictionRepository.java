package dev.proprio.coreapi.analysis;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PredictionRepository {

  public record Prediction(
      UUID sessionId,
      int segmentId,
      String modelVersion,
      String kind,
      double startS,
      double endS,
      String predictedExercise,
      String predictedKind,
      Double confidence,
      Integer repCount,
      String modelName,
      String profileId,
      String transformId) {}

  private static final String LATEST =
      "select p.* from segment_predictions p"
          + " join sessions s on s.session_id = p.session_id"
          + " where s.user_id = :userId and p.session_id in (:ids)"
          + " and p.model_version = (select p2.model_version from segment_predictions p2"
          + "   where p2.session_id = p.session_id order by p2.created_at desc, p2.model_version limit 1)"
          + " order by p.session_id, p.segment_id";

  private final JdbcClient jdbc;

  public PredictionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Insert or replace one segment's prediction (idempotent on the primary key). */
  public void upsert(Prediction p, Instant now) {
    jdbc.sql(
            "insert into segment_predictions (session_id, segment_id, model_version, kind, start_s,"
                + " end_s, predicted_exercise, predicted_kind, confidence, rep_count, model_name,"
                + " profile_id, transform_id, created_at) values (:sid, :seg, :mv, :kind, :start,"
                + " :end, :ex, :pk, :conf, :reps, :mn, :profile, :transform, :now)"
                + " on conflict (session_id, segment_id, model_version) do update set"
                + " kind = excluded.kind, start_s = excluded.start_s, end_s = excluded.end_s,"
                + " predicted_exercise = excluded.predicted_exercise,"
                + " predicted_kind = excluded.predicted_kind, confidence = excluded.confidence,"
                + " rep_count = excluded.rep_count, model_name = excluded.model_name,"
                + " profile_id = excluded.profile_id, transform_id = excluded.transform_id,"
                + " created_at = excluded.created_at")
        .param("sid", p.sessionId())
        .param("seg", p.segmentId())
        .param("mv", p.modelVersion())
        .param("kind", p.kind())
        .param("start", p.startS())
        .param("end", p.endS())
        .param("ex", p.predictedExercise())
        .param("pk", p.predictedKind())
        .param("conf", p.confidence())
        .param("reps", p.repCount())
        .param("mn", p.modelName())
        .param("profile", p.profileId())
        .param("transform", p.transformId())
        .param("now", java.sql.Timestamp.from(now))
        .update();
  }

  /** Latest model version's predictions of one session, only if the session belongs to userId. */
  public List<Prediction> latestForOwned(UUID userId, UUID sessionId) {
    return latestForOwned(userId, List.of(sessionId));
  }

  public List<Prediction> latestForOwned(UUID userId, Collection<UUID> sessionIds) {
    if (sessionIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(LATEST)
        .param("userId", userId)
        .param("ids", sessionIds)
        .query(
            (rs, i) ->
                new Prediction(
                    rs.getObject("session_id", UUID.class),
                    rs.getInt("segment_id"),
                    rs.getString("model_version"),
                    rs.getString("kind"),
                    rs.getDouble("start_s"),
                    rs.getDouble("end_s"),
                    rs.getString("predicted_exercise"),
                    rs.getString("predicted_kind"),
                    (Double) rs.getObject("confidence"),
                    (Integer) rs.getObject("rep_count"),
                    rs.getString("model_name"),
                    rs.getString("profile_id"),
                    rs.getString("transform_id")))
        .list();
  }

  public long count(UUID sessionId) {
    return jdbc.sql("select count(*) from segment_predictions where session_id = :sid")
        .param("sid", sessionId)
        .query(Long.class)
        .single();
  }
}
