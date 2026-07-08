package dev.nini.memstore.extraction;

import dev.nini.memstore.domain.FactEntity;

/**
 * Decides how a new fact candidate relates to existing facts and persists the
 * result (SPEC R4). Plan 04 ships a no-op implementation that simply stores the
 * candidate; Plan 06 replaces it with real conflict detection and resolution.
 */
public interface ConflictResolver {

    /**
     * Outcome of resolving a candidate: the fact that now represents it, and
     * whether a new fact row was created ({@code false} when the candidate was
     * classified as a duplicate and the existing fact was confirmed instead).
     */
    record Resolution(FactEntity fact, boolean createdNew) {
    }

    /**
     * Persists the candidate, resolving any conflict with existing active facts.
     *
     * @return the resolution carrying the fact that now represents this
     *         candidate (newly created, or the existing one when the candidate
     *         was a duplicate)
     */
    Resolution handleNewFact(FactCandidate candidate);
}
