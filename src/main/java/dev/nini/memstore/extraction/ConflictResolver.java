package dev.nini.memstore.extraction;

import dev.nini.memstore.domain.FactEntity;

/**
 * Decides how a new fact candidate relates to existing facts and persists the
 * result (SPEC R4). Plan 04 ships a no-op implementation that simply stores the
 * candidate; Plan 06 replaces it with real conflict detection and resolution.
 */
public interface ConflictResolver {

    /**
     * Persists the candidate, resolving any conflict with existing active facts.
     *
     * @return the fact that now represents this candidate (newly created, or the
     *         existing one when the candidate was a duplicate)
     */
    FactEntity handleNewFact(FactCandidate candidate);
}
