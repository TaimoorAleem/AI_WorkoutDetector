package dev.proprio.coreapi.labels;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "labels")
public class LabelEntity {

  @Id
  @Column(name = "session_id")
  private UUID sessionId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> document;

  /** Optimistic-concurrency counter; exposed as the ETag. */
  @Column(nullable = false)
  private int version;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected LabelEntity() {}

  public LabelEntity(UUID sessionId, Map<String, Object> document, Instant updatedAt) {
    this.sessionId = sessionId;
    this.document = document;
    this.version = 1;
    this.updatedAt = updatedAt;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public Map<String, Object> getDocument() {
    return document;
  }

  public int getVersion() {
    return version;
  }
}
