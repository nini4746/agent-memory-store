package dev.nini.memstore.extraction;

import dev.nini.memstore.domain.FactType;
import java.util.List;
import java.util.UUID;

/**
 * A validated fact ready to be persisted or resolved against existing facts.
 * Carries the session and max source-utterance sequence so the conflict resolver
 * (Plan 06) can apply the newest-session / newest-utterance priority rules.
 */
public record FactCandidate(
        String statement,
        FactType type,
        double confidence,
        float[] embedding,
        String embeddingModel,
        List<UUID> provenanceUtteranceIds,
        UUID sessionId,
        int maxSeq,
        boolean explicitCorrection) {
}
