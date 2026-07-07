package dev.nini.memstore.repo;

import dev.nini.memstore.domain.FactRelationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactRelationRepository extends JpaRepository<FactRelationEntity, UUID> {

    List<FactRelationEntity> findByFromFactId(UUID fromFactId);

    List<FactRelationEntity> findByToFactId(UUID toFactId);
}
