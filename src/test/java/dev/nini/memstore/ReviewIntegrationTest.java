package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.maintenance.MaintenanceJob;
import dev.nini.memstore.maintenance.ReviewStep;
import dev.nini.memstore.search.MemorySearchService;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Plan 08 verification of scoring penalty and LLM-call budget (SPEC R6-A3, R6-A4). */
@SpringBootTest
class ReviewIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    FactStore factStore;
    @Autowired
    MemorySearchService search;
    @Autowired
    MaintenanceJob maintenanceJob;
    @Autowired
    ReviewStep reviewStep;
    @Autowired
    EmbeddingProvider embeddingProvider;

    private UUID utterance() {
        UUID session = sessionLog.append(null, "s",
                List.of(new SessionLogService.LogEntry("user", "seed")));
        return sessionLog.utterancesForSession(session).get(0).getId();
    }

    @Test
    void r6a3_needsReviewFactRanksBelowIdenticalActiveFact() {
        UUID utteranceId = utterance();
        String statement = "prefers the dark theme in the editor";
        float[] emb = embeddingProvider.embed(statement);
        String model = embeddingProvider.modelId();
        FactEntity clean = factStore.save(new FactStore.NewFact(
                statement, FactType.PREFERENCE, 0.9, emb, model, false), List.of(utteranceId));
        FactEntity flagged = factStore.save(new FactStore.NewFact(
                statement, FactType.PREFERENCE, 0.9, emb, model, true), List.of(utteranceId));

        var result = search.search(statement, 2000, null, false);
        double cleanScore = scoreOf(result, clean.getId());
        double flaggedScore = scoreOf(result, flagged.getId());
        assertThat(cleanScore).isGreaterThan(flaggedScore);
        assertThat(result.hits().get(0).factId()).isEqualTo(clean.getId());
    }

    private double scoreOf(MemorySearchService.SearchResponse r, UUID id) {
        return r.hits().stream().filter(h -> h.factId().equals(id)).findFirst()
                .orElseThrow().score();
    }

    @Test
    void r6a4_maintenanceRespectsLlmCallBudget() {
        UUID utteranceId = utterance();
        String model = embeddingProvider.modelId();
        int flaggedCount = 60;
        for (int i = 0; i < flaggedCount; i++) {
            String statement = "isolated observation " + randomAlpha(i);
            factStore.save(new FactStore.NewFact(statement, FactType.EVENT, 0.9,
                    embeddingProvider.embed(statement), model, true), List.of(utteranceId));
        }

        maintenanceJob.runOnce();

        // Batch is capped at 20; the call budget of 50 is never exceeded (N5).
        assertThat(reviewStep.lastLlmCalls()).isLessThanOrEqualTo(50);
        assertThat(reviewStep.lastLlmCalls()).isLessThanOrEqualTo(20);

        long stillFlagged = factStore.findByStatus(FactStatus.ACTIVE).stream()
                .filter(FactEntity::isNeedsReview).count();
        assertThat(stillFlagged).isGreaterThanOrEqualTo(flaggedCount - 20L);
    }

    private static String randomAlpha(int seed) {
        java.util.Random r = new java.util.Random(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('a' + r.nextInt(26)));
        }
        return sb.toString();
    }
}
