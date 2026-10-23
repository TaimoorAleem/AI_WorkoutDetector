package dev.proprio.coreapi.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class SessionEntity {

  public static final String CREATED = "CREATED";
  public static final String UPLOADED = "UPLOADED";
  public static final String ANALYZING = "ANALYZING";
  public static final String ANALYZED = "ANALYZED";
  public static final String FAILED = "FAILED";

  @Id
  @Column(name = "session_id")
  private UUID sessionId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(nullable = false)
  private String kind;

  @Column(nullable = false)
  private String status;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "duration_s")
  private Double durationS;

  @Column(name = "object_key", nullable = false)
  private String objectKey;

  @Column(nullable = false, columnDefinition = "char(64)")
  private String sha256;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "raw_format", nullable = false)
  private int rawFormat;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "uploaded_at")
  private Instant uploadedAt;

  @Column(name = "analyzed_at")
  private Instant analyzedAt;

  @Column(name = "failure_reason")
  private String failureReason;

  protected SessionEntity() {}

  public SessionEntity(UUID sessionId, UUID userId, String objectKey, Instant createdAt) {
    this.sessionId = sessionId;
    this.userId = userId;
    this.objectKey = objectKey;
    this.createdAt = createdAt;
    this.status = CREATED;
  }

  /** Applies (or re-applies, while still CREATED) the metadata declared at registration. */
  public void describe(
      UUID deviceId,
      String kind,
      Instant startedAt,
      Double durationS,
      String sha256,
      long sizeBytes,
      int rawFormat) {
    this.deviceId = deviceId;
    this.kind = kind;
    this.startedAt = startedAt;
    this.durationS = durationS;
    this.sha256 = sha256;
    this.sizeBytes = sizeBytes;
    this.rawFormat = rawFormat;
  }

  public void markUploaded(Instant now) {
    this.status = UPLOADED;
    this.uploadedAt = now;
  }

  public void markAnalyzed(Instant now) {
    this.status = ANALYZED;
    this.analyzedAt = now;
    this.failureReason = null;
  }

  public void markFailed(Instant now, String reason) {
    this.status = FAILED;
    this.analyzedAt = now;
    this.failureReason = reason;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getDeviceId() {
    return deviceId;
  }

  public String getKind() {
    return kind;
  }

  public String getStatus() {
    return status;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Double getDurationS() {
    return durationS;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getSha256() {
    return sha256;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public int getRawFormat() {
    return rawFormat;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUploadedAt() {
    return uploadedAt;
  }

  public Instant getAnalyzedAt() {
    return analyzedAt;
  }

  public String getFailureReason() {
    return failureReason;
  }
}
