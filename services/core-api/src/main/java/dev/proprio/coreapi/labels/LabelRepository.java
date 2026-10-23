package dev.proprio.coreapi.labels;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Labels are only reachable through their owning session's user. */
public interface LabelRepository extends JpaRepository<LabelEntity, UUID> {

  @Query(
      "select l from LabelEntity l, SessionEntity s where l.sessionId = s.sessionId"
          + " and s.userId = :userId and l.sessionId = :sessionId")
  Optional<LabelEntity> findOwned(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

  @Query(
      "select l from LabelEntity l, SessionEntity s where l.sessionId = s.sessionId"
          + " and s.userId = :userId and l.sessionId in :sessionIds")
  List<LabelEntity> findOwned(
      @Param("userId") UUID userId, @Param("sessionIds") Collection<UUID> sessionIds);

  /** Compare-and-set on the version; returns 0 if someone else updated meanwhile. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update LabelEntity l set l.document = :doc, l.version = l.version + 1, l.updatedAt = :now"
          + " where l.sessionId = :sessionId and l.version = :version")
  int updateIfVersion(
      @Param("sessionId") UUID sessionId,
      @Param("version") int version,
      @Param("doc") Map<String, Object> doc,
      @Param("now") Instant now);
}
