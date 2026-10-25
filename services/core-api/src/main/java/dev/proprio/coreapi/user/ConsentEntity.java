package dev.proprio.coreapi.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consents")
public class ConsentEntity {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "training_use", nullable = false)
  private boolean trainingUse;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  protected ConsentEntity() {}

  public ConsentEntity(UUID userId, boolean trainingUse, Instant recordedAt) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.trainingUse = trainingUse;
    this.recordedAt = recordedAt;
  }

  public boolean isTrainingUse() {
    return trainingUse;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }
}
