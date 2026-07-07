package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.search.MemorySearchService;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Plan 05 integration verification (SPEC R3-A4, R3-A5). */
@SpringBootTest
class SearchIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    FactStore factStore;
    @Autowired
    MemorySearchService search;
    @Autowired
    EmbeddingProvider embeddingProvider;
    @Autowired
    JdbcTemplate jdbc;

    private UUID anUtterance() {
        UUID sessionId = sessionLog.append(null, "test",
                List.of(new SessionLogService.LogEntry("user", "seed")));
        return sessionLog.utterancesForSession(sessionId).get(0).getId();
    }

    @Test
    void r3a4_defaultSearchExcludesInactiveFacts() {
        UUID utteranceId = anUtterance();
        String model = embeddingProvider.modelId();
        FactEntity active = factStore.save(new FactStore.NewFact(
                "uses postgresql for storage", FactType.DECISION, 0.9,
                embeddingProvider.embed("uses postgresql for storage"), model, false),
                List.of(utteranceId));
        FactEntity superseded = factStore.save(new FactStore.NewFact(
                "uses mysql for storage", FactType.DECISION, 0.9,
                embeddingProvider.embed("uses mysql for storage"), model, false),
                List.of(utteranceId));
        factStore.transition(superseded.getId(), FactStatus.SUPERSEDED, active.getId());

        var defaultResult = search.search("database storage", 2000, null, false);
        assertThat(defaultResult.hits()).extracting(MemorySearchService.MemoryHit::factId)
                .contains(active.getId())
                .doesNotContain(superseded.getId());

        var inclusive = search.search("database storage", 2000, null, true);
        assertThat(inclusive.hits()).extracting(MemorySearchService.MemoryHit::factId)
                .contains(active.getId(), superseded.getId());
    }

    @Test
    void r3a5_searchP95Under500msAt10kFacts() {
        UUID utteranceId = anUtterance();
        String model = embeddingProvider.modelId();
        seedFacts(10_000, utteranceId, model);

        List<Long> timingsMicros = new ArrayList<>();
        // Warm up, then measure the internal search processing.
        for (int i = 0; i < 5; i++) {
            search.search("topic " + i, 2000, null, false);
        }
        for (int i = 0; i < 30; i++) {
            long start = System.nanoTime();
            search.search("topic number " + i, 2000, null, false);
            timingsMicros.add((System.nanoTime() - start) / 1000);
        }
        timingsMicros.sort(Long::compareTo);
        long p95Micros = timingsMicros.get((int) Math.ceil(0.95 * timingsMicros.size()) - 1);
        double p95Millis = p95Micros / 1000.0;
        System.out.println("R3-A5 p95 search latency = " + p95Millis + " ms");
        assertThat(p95Millis).isLessThan(500.0);
    }

    private void seedFacts(int count, UUID utteranceId, String model) {
        OffsetDateTime now = OffsetDateTime.now();
        int batch = 500;
        List<Object[]> factRows = new ArrayList<>();
        List<Object[]> provRows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            String statement = "seeded fact about topic " + (i % 250) + " item " + i;
            float[] emb = embeddingProvider.embed(statement);
            factRows.add(new Object[]{id, statement, FactType.EVENT.code(),
                    FactStore.toVectorLiteral(emb), model, 0.7,
                    FactStatus.ACTIVE.code(), false, now, now});
            provRows.add(new Object[]{id, utteranceId});
            if (factRows.size() == batch) {
                flush(factRows, provRows);
                factRows.clear();
                provRows.clear();
            }
        }
        if (!factRows.isEmpty()) {
            flush(factRows, provRows);
        }
    }

    private void flush(List<Object[]> factRows, List<Object[]> provRows) {
        jdbc.batchUpdate(
                "INSERT INTO fact (id, statement, type, embedding, embedding_model, "
                        + "confidence, status, needs_review, created_at, last_confirmed_at) "
                        + "VALUES (?, ?, ?, CAST(? AS vector), ?, ?, ?, ?, ?, ?)",
                factRows);
        jdbc.batchUpdate(
                "INSERT INTO fact_provenance (fact_id, utterance_id) VALUES (?, ?)",
                provRows);
    }
}
