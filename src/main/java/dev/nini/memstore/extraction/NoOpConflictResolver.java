package dev.nini.memstore.extraction;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.service.FactStore;
import org.springframework.stereotype.Component;

/**
 * Placeholder conflict resolver (Plan 04) that stores every candidate as a new
 * fact without inspecting existing facts. Plan 06 supplies a {@code @Primary}
 * implementation that supersedes this one.
 */
@Component
public class NoOpConflictResolver implements ConflictResolver {

    private final FactStore factStore;

    public NoOpConflictResolver(FactStore factStore) {
        this.factStore = factStore;
    }

    @Override
    public FactEntity handleNewFact(FactCandidate candidate) {
        return factStore.save(
                new FactStore.NewFact(candidate.statement(), candidate.type(),
                        candidate.confidence(), candidate.embedding(),
                        candidate.embeddingModel(), false),
                candidate.provenanceUtteranceIds());
    }
}
