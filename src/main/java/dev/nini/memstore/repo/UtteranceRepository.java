package dev.nini.memstore.repo;

import dev.nini.memstore.domain.UtteranceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Append-only repository for utterances. Intentionally does not extend
 * JpaRepository so no update/delete methods are exposed; only insert and read
 * operations are available. The database trigger (V3) is the hard enforcement
 * of immutability (SPEC N1).
 */
public interface UtteranceRepository extends Repository<UtteranceEntity, UUID> {

    UtteranceEntity save(UtteranceEntity utterance);

    Optional<UtteranceEntity> findById(UUID id);

    List<UtteranceEntity> findBySessionIdOrderBySeqAsc(UUID sessionId);

    long count();

    @Query("select coalesce(max(u.seq), -1) from UtteranceEntity u where u.sessionId = ?1")
    int maxSeqForSession(UUID sessionId);
}
