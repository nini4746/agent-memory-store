package dev.nini.memstore.extraction;

import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.UtteranceEntity;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.llm.LlmClient;
import dev.nini.memstore.search.FactVectorSearch;
import dev.nini.memstore.search.ScoredFactRow;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session-level extraction pipeline (SPEC R2): LLM extracts candidate facts,
 * each candidate is embedded and de-duplicated against existing active facts
 * (similarity >= 0.92 plus an LLM same-fact check), and non-duplicates are
 * handed to the conflict resolver for persistence.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);
    private static final double DEDUPE_THRESHOLD = 0.92;
    private static final double CONFIDENCE_EXPLICIT = 0.9;
    private static final double CONFIDENCE_INFERRED = 0.6;

    /** Summary of an extraction run. */
    public record ExtractionResult(int created, int confirmed, int skipped) {
    }

    private final SessionLogService sessionLog;
    private final LlmClient llm;
    private final EmbeddingProvider embeddingProvider;
    private final FactVectorSearch vectorSearch;
    private final FactStore factStore;
    private final ConflictResolver conflictResolver;

    public ExtractionService(SessionLogService sessionLog, LlmClient llm,
                             EmbeddingProvider embeddingProvider,
                             FactVectorSearch vectorSearch, FactStore factStore,
                             ConflictResolver conflictResolver) {
        this.sessionLog = sessionLog;
        this.llm = llm;
        this.embeddingProvider = embeddingProvider;
        this.vectorSearch = vectorSearch;
        this.factStore = factStore;
        this.conflictResolver = conflictResolver;
    }

    @Transactional
    public ExtractionResult extract(UUID sessionId) {
        List<UtteranceEntity> utterances = sessionLog.utterancesForSession(sessionId);
        Map<Integer, UUID> seqToId = new HashMap<>();
        List<LlmClient.UtteranceView> views = new java.util.ArrayList<>();
        for (UtteranceEntity u : utterances) {
            seqToId.put(u.getSeq(), u.getId());
            views.add(new LlmClient.UtteranceView(u.getSeq(), u.getRole(), u.getContent()));
        }

        List<LlmClient.ExtractionCandidate> candidates = llm.extractFacts(views);
        int created = 0;
        int confirmed = 0;
        int skipped = 0;

        for (LlmClient.ExtractionCandidate candidate : candidates) {
            if (candidate.statement() == null || candidate.statement().isBlank()) {
                skipped++;
                continue;
            }
            List<UUID> provenanceIds = new java.util.ArrayList<>();
            int maxSeq = -1;
            for (Integer seq : candidate.sourceSeqs()) {
                UUID id = seqToId.get(seq);
                if (id != null) {
                    provenanceIds.add(id);
                    maxSeq = Math.max(maxSeq, seq);
                }
            }
            if (provenanceIds.isEmpty()) {
                log.warn("rejecting candidate without valid provenance: {}",
                        candidate.statement());
                skipped++;
                continue;
            }

            float[] embedding = embeddingProvider.embed(candidate.statement());
            ScoredFactRow duplicate = findDuplicate(embedding, candidate.statement());
            if (duplicate != null) {
                factStore.confirm(duplicate.id());
                confirmed++;
                continue;
            }

            double confidence = candidate.explicit()
                    ? CONFIDENCE_EXPLICIT : CONFIDENCE_INFERRED;
            FactCandidate fc = new FactCandidate(candidate.statement(), candidate.type(),
                    confidence, embedding, embeddingProvider.modelId(), provenanceIds,
                    sessionId, maxSeq, llm.isCorrection(candidate.statement()));
            conflictResolver.handleNewFact(fc);
            created++;
        }
        return new ExtractionResult(created, confirmed, skipped);
    }

    private ScoredFactRow findDuplicate(float[] embedding, String statement) {
        List<ScoredFactRow> hits = vectorSearch.topK(embedding, 5,
                List.of(FactStatus.ACTIVE));
        for (ScoredFactRow hit : hits) {
            if (hit.similarity() >= DEDUPE_THRESHOLD
                    && llm.isSameFact(statement, hit.statement())) {
                return hit;
            }
        }
        return null;
    }
}
