package dev.nini.memstore.repo;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactRepository extends JpaRepository<FactEntity, UUID> {

    List<FactEntity> findByStatus(FactStatus status);
}
