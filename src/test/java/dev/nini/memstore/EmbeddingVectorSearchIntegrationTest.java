package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.search.FactVectorSearch;
import dev.nini.memstore.search.ScoredFactRow;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan 03 verification: deterministic fake embeddings, cosine ranking, embedding
 * model mixing rejection, and index presence.
 */
@SpringBootTest
class EmbeddingVectorSearchIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    FactStore factStore;
    @Autowired
    FactVectorSearch vectorSearch;
    @Autowired
    EmbeddingProvider embeddingProvider;
    @Autowired
    JdbcTemplate jdbc;

    private UUID seedFact(String statement, String embeddingModel) {
        UUID sessionId = sessionLog.append(null, "test",
                List.of(new SessionLogService.LogEntry("user", statement)));
        UUID utteranceId = sessionLog.utterancesForSession(sessionId).get(0).getId();
        float[] embedding = embeddingProvider.embed(statement);
        FactEntity fact = factStore.save(
                new FactStore.NewFact(statement, FactType.PREFERENCE, 0.9,
                        embedding, embeddingModel, false),
                List.of(utteranceId));
        return fact.getId();
    }

    @Test
    void similarSentenceRanksAboveUnrelated() {
        String model = embeddingProvider.modelId();
        UUID similar = seedFact("the user prefers dark mode in the editor", model);
        UUID unrelated = seedFact("deploy the service to a kubernetes cluster", model);

        float[] query = embeddingProvider.embed(
                "user likes dark mode editor preference");
        List<ScoredFactRow> hits = vectorSearch.topK(query, 10,
                List.of(FactStatus.ACTIVE));

        assertThat(hits).extracting(ScoredFactRow::id).contains(similar, unrelated);
        double simScore = hits.stream().filter(h -> h.id().equals(similar))
                .findFirst().orElseThrow().similarity();
        double unrelatedScore = hits.stream().filter(h -> h.id().equals(unrelated))
                .findFirst().orElseThrow().similarity();
        assertThat(simScore).isGreaterThan(unrelatedScore);
        assertThat(simScore).isBetween(0.0, 1.0);
        assertThat(unrelatedScore).isBetween(0.0, 1.0);
    }

    @Test
    void factsWithDifferentEmbeddingModelAreExcluded() {
        String model = embeddingProvider.modelId();
        UUID current = seedFact("uses postgres as the database", model);
        UUID otherModel = seedFact("uses postgres as the database engine",
                "some-other-model");

        float[] query = embeddingProvider.embed("postgres database");
        List<ScoredFactRow> hits = vectorSearch.topK(query, 10,
                List.of(FactStatus.ACTIVE));

        assertThat(hits).extracting(ScoredFactRow::id)
                .contains(current)
                .doesNotContain(otherModel);
    }

    @Test
    void vectorIndexExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_fact_embedding_hnsw'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
