package dev.nini.memstore.search;

import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A candidate fact returned by vector search, carrying the fields needed for
 * downstream scoring (Plan 05) plus the normalized [0,1] cosine similarity.
 */
public record ScoredFactRow(
        UUID id,
        String statement,
        FactType type,
        double confidence,
        FactStatus status,
        boolean needsReview,
        OffsetDateTime lastConfirmedAt,
        double similarity) {
}
