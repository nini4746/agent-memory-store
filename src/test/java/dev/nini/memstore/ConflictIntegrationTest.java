package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.extraction.ConflictResolver;
import dev.nini.memstore.extraction.ExtractionService;
import dev.nini.memstore.extraction.FactCandidate;
import dev.nini.memstore.search.MemorySearchService;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Plan 06 verification (SPEC R4-A1..A4). */
@SpringBootTest
class ConflictIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String OLD = "The project database uses PostgreSQL version 15";
    private static final String NEW = "The project database uses PostgreSQL version 17";

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    ExtractionService extraction;
    @Autowired
    ConflictResolver conflictResolver;
    @Autowired
    FactStore factStore;
    @Autowired
    MemorySearchService search;
    @Autowired
    EmbeddingProvider embeddingProvider;

    @Test
    void r4a1_newerSessionWinsAcrossSessions() {
        OffsetDateTime march = OffsetDateTime.now().minusMonths(4);
        OffsetDateTime june = OffsetDateTime.now().minusMonths(1);
        UUID marchSession = sessionLog.append(null, "march",
                List.of(new SessionLogService.LogEntry("user", OLD)), march);
        extraction.extract(marchSession);
        UUID juneSession = sessionLog.append(null, "june",
                List.of(new SessionLogService.LogEntry("user", NEW)), june);
        extraction.extract(juneSession);

        List<FactEntity> active = factStore.findByStatus(FactStatus.ACTIVE);
        assertThat(active).hasSize(1);
        FactEntity winner = active.get(0);
        assertThat(winner.getStatement()).contains("17");

        List<FactEntity> superseded = factStore.findByStatus(FactStatus.SUPERSEDED);
        assertThat(superseded).hasSize(1);
        assertThat(superseded.get(0).getStatement()).contains("15");
        assertThat(superseded.get(0).getSupersededBy()).isEqualTo(winner.getId());

        var result = search.search("PostgreSQL version", 2000, null, false);
        assertThat(result.hits()).extracting(MemorySearchService.MemoryHit::factId)
                .containsExactly(winner.getId());
    }

    @Test
    void r4a2_laterUtteranceWinsWithinSession() {
        UUID session = sessionLog.append(null, "single", List.of(
                new SessionLogService.LogEntry("user", OLD),
                new SessionLogService.LogEntry("user", NEW)));
        extraction.extract(session);

        List<FactEntity> active = factStore.findByStatus(FactStatus.ACTIVE);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatement()).contains("17");
        assertThat(factStore.findByStatus(FactStatus.SUPERSEDED)).hasSize(1);
    }

    @Test
    void r4a3_sameUtteranceKeepsBothAsNeedsReview() {
        UUID session = sessionLog.append(null, "same",
                List.of(new SessionLogService.LogEntry("user", "seed utterance")));
        UUID utteranceId = sessionLog.utterancesForSession(session).get(0).getId();

        FactEntity a = conflictResolver.handleNewFact(candidate(OLD, session, utteranceId, 0));
        FactEntity b = conflictResolver.handleNewFact(candidate(NEW, session, utteranceId, 0));

        FactEntity reloadedA = factStore.find(a.getId()).orElseThrow();
        FactEntity reloadedB = factStore.find(b.getId()).orElseThrow();
        assertThat(reloadedA.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(reloadedB.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(reloadedA.isNeedsReview()).isTrue();
        assertThat(reloadedB.isNeedsReview()).isTrue();
    }

    @Test
    void r4a4_retractedStatementReappearsAsNeedsReviewWithoutRevival() {
        UUID session = sessionLog.append(null, "s",
                List.of(new SessionLogService.LogEntry("user", "seed")));
        UUID utteranceId = sessionLog.utterancesForSession(session).get(0).getId();

        FactEntity original = conflictResolver.handleNewFact(
                candidate(OLD, session, utteranceId, 0));
        factStore.transition(original.getId(), FactStatus.RETRACTED, null);

        FactEntity reappeared = conflictResolver.handleNewFact(
                candidate(OLD, session, utteranceId, 0));

        assertThat(reappeared.getId()).isNotEqualTo(original.getId());
        assertThat(reappeared.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(reappeared.isNeedsReview()).isTrue();
        assertThat(factStore.find(original.getId()).orElseThrow().getStatus())
                .isEqualTo(FactStatus.RETRACTED);
    }

    private FactCandidate candidate(String statement, UUID sessionId, UUID utteranceId,
                                    int seq) {
        return new FactCandidate(statement, FactType.DECISION, 0.9,
                embeddingProvider.embed(statement), embeddingProvider.modelId(),
                List.of(utteranceId), sessionId, seq, false);
    }
}
