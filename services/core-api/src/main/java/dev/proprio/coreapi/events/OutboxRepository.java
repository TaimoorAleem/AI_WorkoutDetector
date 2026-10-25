package dev.proprio.coreapi.events;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Transactional outbox: rows are written in the caller's transaction and drained by a poller. */
@Repository
public class OutboxRepository {

  public enum Kind {
    AMQP,
    S3_DELETE
  }

  public record Row(
      UUID id, Kind kind, String exchange, String routingKey, String payload, int attempts) {}

  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** {@code id} doubles as the event_id that consumers dedupe on. */
  public void enqueueEvent(
      UUID id, String exchange, String routingKey, String payload, UUID userId) {
    jdbc.sql(
            "insert into outbox (id, kind, exchange, routing_key, payload, user_id)"
                + " values (:id, 'AMQP', :exchange, :rk, :payload, :userId)")
        .param("id", id)
        .param("exchange", exchange)
        .param("rk", routingKey)
        .param("payload", payload)
        .param("userId", userId)
        .update();
  }

  public void enqueueObjectDelete(String objectKey) {
    jdbc.sql("insert into outbox (id, kind, payload) values (:id, 'S3_DELETE', :payload)")
        .param("id", UUID.randomUUID())
        .param("payload", objectKey)
        .update();
  }

  /** Oldest unpublished rows, locked so concurrent pollers never publish the same row. */
  public List<Row> lockPending(int limit) {
    return jdbc.sql(
            "select id, kind, exchange, routing_key, payload, attempts from outbox"
                + " where published_at is null order by created_at, id limit :limit"
                + " for update skip locked")
        .param("limit", limit)
        .query(
            (rs, i) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    Kind.valueOf(rs.getString("kind")),
                    rs.getString("exchange"),
                    rs.getString("routing_key"),
                    rs.getString("payload"),
                    rs.getInt("attempts")))
        .list();
  }

  public void markPublished(UUID id) {
    jdbc.sql("update outbox set published_at = now() where id = :id").param("id", id).update();
  }

  public void markAttempted(UUID id) {
    jdbc.sql("update outbox set attempts = attempts + 1 where id = :id").param("id", id).update();
  }

  /** Account deletion: unpublished events about the user's sessions must not be sent. */
  public int deletePendingEventsForUser(UUID userId) {
    return jdbc.sql(
            "delete from outbox where user_id = :userId and kind = 'AMQP' and published_at is null")
        .param("userId", userId)
        .update();
  }

  public long countPending() {
    return jdbc.sql("select count(*) from outbox where published_at is null")
        .query(Long.class)
        .single();
  }
}
