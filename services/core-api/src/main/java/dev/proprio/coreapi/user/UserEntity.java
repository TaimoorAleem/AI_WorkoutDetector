package dev.proprio.coreapi.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "participant_id", nullable = false)
  private String participantId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected UserEntity() {}

  public UserEntity(
      UUID id, String email, String passwordHash, String participantId, Instant createdAt) {
    this.id = id;
    this.email = email;
    this.passwordHash = passwordHash;
    this.participantId = participantId;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getParticipantId() {
    return participantId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  /** Tombstones the account: personal data is blanked and the email is freed for reuse. */
  public void anonymize(Instant now) {
    this.email = "deleted+" + id + "@invalid.proprio";
    this.passwordHash = "!";
    this.participantId = "deleted";
    this.deletedAt = now;
  }
}
