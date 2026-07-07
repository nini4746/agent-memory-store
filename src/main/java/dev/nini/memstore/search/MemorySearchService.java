package dev.nini.memstore.search;

import dev.nini.memstore.config.BudgetProperties;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.repo.FactProvenanceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retrieval and routing service (SPEC R3): weighted scoring, type/status
 * filtering, and greedy token-budget packing. Retrieval never updates
 * last_confirmed_at (SPEC R5-A4 - echo-chamber prevention).
 */
@Service
public class MemorySearchService {

    private static final int VECTOR_TOP_K = 50;

    /** A single returned memory with its provenance and score. */
    public record MemoryHit(UUID factId, String statement, FactType type,
                            double confidence, boolean needsReview,
                            List<UUID> provenanceUtteranceIds, double score) {
    }

    /** Search response: hits within budget, the estimated tokens, and warnings. */
    public record SearchResponse(List<MemoryHit> hits, int estimatedTokens,
                                 String warning) {
    }

    private record Candidate(ScoredFactRow row, double score, int tokenCost)
            implements BudgetPacker.Weighted {
    }

    private final EmbeddingProvider embeddingProvider;
    private final FactVectorSearch vectorSearch;
    private final ScoringPolicy scoringPolicy;
    private final FactProvenanceRepository provenance;
    private final BudgetProperties budget;
    private final Clock clock;

    public MemorySearchService(EmbeddingProvider embeddingProvider,
                               FactVectorSearch vectorSearch,
                               ScoringPolicy scoringPolicy,
                               FactProvenanceRepository provenance,
                               BudgetProperties budget, Clock clock) {
        this.embeddingProvider = embeddingProvider;
        this.vectorSearch = vectorSearch;
        this.scoringPolicy = scoringPolicy;
        this.provenance = provenance;
        this.budget = budget;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SearchResponse search(String query, Integer budgetTokens,
                                 List<FactType> types, boolean includeInactive) {
        int effectiveBudget = clampBudget(budgetTokens);
        float[] queryEmbedding = embeddingProvider.embed(query);
        List<FactStatus> statuses = includeInactive
                ? List.of(FactStatus.values())
                : List.of(FactStatus.ACTIVE);

        List<ScoredFactRow> rows = vectorSearch.topK(queryEmbedding, VECTOR_TOP_K, statuses);
        OffsetDateTime now = OffsetDateTime.now(clock);

        List<Candidate> candidates = new ArrayList<>();
        for (ScoredFactRow row : rows) {
            if (types != null && !types.isEmpty() && !types.contains(row.type())) {
                continue;
            }
            double score = scoringPolicy.score(row, now);
            int cost = TokenEstimator.estimate(row.statement());
            candidates.add(new Candidate(row, score, cost));
        }

        BudgetPacker.Packed<Candidate> packed =
                BudgetPacker.pack(candidates, effectiveBudget);

        List<MemoryHit> hits = new ArrayList<>();
        for (Candidate c : packed.items()) {
            hits.add(new MemoryHit(c.row().id(), c.row().statement(), c.row().type(),
                    c.row().confidence(), c.row().needsReview(),
                    provenanceIds(c.row().id()), c.score()));
        }
        return new SearchResponse(hits, packed.estimatedTokens(), packed.warning());
    }

    private int clampBudget(Integer requested) {
        int value = requested == null ? budget.getDefaultTokens() : requested;
        return Math.max(1, Math.min(budget.getMaxTokens(), value));
    }

    private List<UUID> provenanceIds(UUID factId) {
        List<UUID> ids = new ArrayList<>();
        provenance.findByKeyFactId(factId).forEach(p -> ids.add(p.getUtteranceId()));
        return ids;
    }
}
