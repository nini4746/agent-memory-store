package dev.nini.memstore.extraction;

import java.util.UUID;

/**
 * Correction-propagation hook (SPEC R6). When a fact is invalidated
 * (superseded/retracted), related facts within depth 2 should be flagged for
 * review. Plan 06 ships a no-op; Plan 08 supplies the real implementation.
 */
public interface PropagationService {

    /** Propagates review flags outward from an invalidated fact. */
    void propagateFrom(UUID invalidatedFactId);
}
