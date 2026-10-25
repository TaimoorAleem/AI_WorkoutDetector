package dev.proprio.coreapi.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRepository extends JpaRepository<ConsentEntity, UUID> {

  Optional<ConsentEntity> findFirstByUserIdOrderByRecordedAtDesc(UUID userId);

  List<ConsentEntity> findByUserIdOrderByRecordedAtAsc(UUID userId);
}
