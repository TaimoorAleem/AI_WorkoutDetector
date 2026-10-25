package dev.proprio.coreapi.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

  /**
   * Row-locked so concurrent uses of the same token serialize (one rotates, the other is reuse).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  /** User-scoped lookup, used by logout. */
  Optional<RefreshTokenEntity> findByTokenHashAndUserId(String tokenHash, UUID userId);

  @Modifying
  @Query(
      "update RefreshTokenEntity t set t.revokedAt = :now"
          + " where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

  @Modifying
  @Query("delete from RefreshTokenEntity t where t.userId = :userId")
  int deleteAllForUser(@Param("userId") UUID userId);
}
