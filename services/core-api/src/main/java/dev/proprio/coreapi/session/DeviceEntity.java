package dev.proprio.coreapi.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
public class DeviceEntity {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String manufacturer;

  @Column(nullable = false)
  private String model;

  @Column(name = "sdk_int")
  private Integer sdkInt;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  protected DeviceEntity() {}

  public DeviceEntity(
      UUID userId, String manufacturer, String model, Integer sdkInt, Instant firstSeenAt) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.manufacturer = manufacturer;
    this.model = model;
    this.sdkInt = sdkInt;
    this.firstSeenAt = firstSeenAt;
  }

  public UUID getId() {
    return id;
  }

  public String getModel() {
    return model;
  }
}
