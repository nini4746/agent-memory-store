package dev.nini.memstore.extraction;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.RelationType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit correction path (SPEC R4). Retracts the target fact, stores the
 * correction as a high-confidence fact with synthetic provenance, links them,
 * and triggers correction propagation.
 */
@Service
public class CorrectionService {

    private static final double CORRECTION_CONFIDENCE = 0.95;

    private final FactStore factStore;
    private final SessionLogService sessionLog;
    private final EmbeddingProvider embeddingProvider;
    private final PropagationService propagation;

    public CorrectionService(FactStore factStore, SessionLogService sessionLog,
                             EmbeddingProvider embeddingProvider,
                             PropagationService propagation) {
        this.factStore = factStore;
        this.sessionLog = sessionLog;
        this.embeddingProvider = embeddingProvider;
        this.propagation = propagation;
    }

    @Transactional
    public FactEntity correct(UUID factId, String correctionStatement) {
        FactEntity target = factStore.find(factId).orElseThrow(
                () -> new IllegalArgumentException("no such fact: " + factId));

        // Synthetic session/utterance so the correction fact keeps provenance (N4).
        UUID sessionId = sessionLog.append(null, "correction",
                List.of(new SessionLogService.LogEntry("user", correctionStatement)));
        UUID utteranceId = sessionLog.utterancesForSession(sessionId).get(0).getId();

        float[] embedding = embeddingProvider.embed(correctionStatement);
        FactEntity correction = factStore.save(
                new FactStore.NewFact(correctionStatement, target.getType(),
                        CORRECTION_CONFIDENCE, embedding, embeddingProvider.modelId(), false),
                List.of(utteranceId));

        factStore.transition(target.getId(), FactStatus.RETRACTED, correction.getId());
        factStore.addRelation(correction.getId(), target.getId(), RelationType.SUPERSEDES);
        propagation.propagateFrom(target.getId());
        return correction;
    }
}
