package dev.nini.memstore.repo;

import dev.nini.memstore.domain.FactProvenanceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactProvenanceRepository
        extends JpaRepository<FactProvenanceEntity, FactProvenanceEntity.Key> {

    List<FactProvenanceEntity> findByKeyFactId(UUID factId);

    List<FactProvenanceEntity> findByKeyUtteranceId(UUID utteranceId);
}
