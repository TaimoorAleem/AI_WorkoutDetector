package dev.proprio.coreapi.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {

  Optional<DeviceEntity> findByUserIdAndModel(UUID userId, String model);

  Optional<DeviceEntity> findByIdAndUserId(UUID id, UUID userId);

  @Modifying
  @Query("delete from DeviceEntity d where d.userId = :userId")
  int deleteAllForUser(@Param("userId") UUID userId);
}
