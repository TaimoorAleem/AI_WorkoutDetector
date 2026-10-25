package dev.proprio.coreapi.session;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every method is scoped by user id, except the consumer-only {@link #findById}. */
public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

  Optional<SessionEntity> findBySessionIdAndUserId(UUID sessionId, UUID userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from SessionEntity s where s.sessionId = :sessionId and s.userId = :userId")
  Optional<SessionEntity> findForUpdate(
      @Param("sessionId") UUID sessionId, @Param("userId") UUID userId);

  List<SessionEntity> findByUserIdAndSessionIdIn(UUID userId, Collection<UUID> sessionIds);

  List<SessionEntity> findByUserId(UUID userId);

  List<SessionEntity> findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
      UUID userId, Instant from, Instant toExclusive);

  @Modifying
  @Query("delete from SessionEntity s where s.userId = :userId")
  int deleteAllForUser(@Param("userId") UUID userId);
}
