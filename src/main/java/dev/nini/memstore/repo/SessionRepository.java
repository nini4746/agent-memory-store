package dev.nini.memstore.repo;

import dev.nini.memstore.domain.SessionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {
}
