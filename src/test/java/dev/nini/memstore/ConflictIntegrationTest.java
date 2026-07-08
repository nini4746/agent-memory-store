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

        FactEntity a = conflictResolver.handleNewFact(
                candidate(OLD, session, utteranceId, 0)).fact();
        FactEntity b = conflictResolver.handleNewFact(
                candidate(NEW, session, utteranceId, 0)).fact();

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
                candidate(OLD, session, utteranceId, 0)).fact();
        factStore.transition(original.getId(), FactStatus.RETRACTED, null);

        FactEntity reappeared = conflictResolver.handleNewFact(
                candidate(OLD, session, utteranceId, 0)).fact();

        assertThat(reappeared.getId()).isNotEqualTo(original.getId());
        assertThat(reappeared.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(reappeared.isNeedsReview()).isTrue();
        assertThat(factStore.find(original.getId()).orElseThrow().getStatus())
                .isEqualTo(FactStatus.RETRACTED);
    }

    @Test
    void r4_explicitCorrectionOutranksNewerSession() {
        // Existing fact lives in the NEWER session; the correction candidate
        // comes from an OLDER session. Priority "explicit correction > newest
        // session" means the correction must still win, and the loser must be
        // retracted (not superseded) with the correction stored at 0.95.
        OffsetDateTime june = OffsetDateTime.now().minusMonths(1);
        OffsetDateTime march = OffsetDateTime.now().minusMonths(4);
        UUID juneSession = sessionLog.append(null, "june",
                List.of(new SessionLogService.LogEntry("user", NEW)), june);
        UUID juneUtterance = sessionLog.utterancesForSession(juneSession).get(0).getId();
        FactEntity existing = conflictResolver.handleNewFact(
                candidate(NEW, juneSession, juneUtterance, 0)).fact();

        String correctionStatement =
                "Correction: the project database uses PostgreSQL version 15";
        UUID marchSession = sessionLog.append(null, "march",
                List.of(new SessionLogService.LogEntry("user", correctionStatement)), march);
        UUID marchUtterance = sessionLog.utterancesForSession(marchSession).get(0).getId();
        FactEntity correction = conflictResolver.handleNewFact(candidate(
                correctionStatement, marchSession, marchUtterance, 0, true)).fact();

        FactEntity reloadedExisting = factStore.find(existing.getId()).orElseThrow();
        assertThat(reloadedExisting.getStatus()).isEqualTo(FactStatus.RETRACTED);
        assertThat(reloadedExisting.getSupersededBy()).isEqualTo(correction.getId());

        FactEntity reloadedCorrection = factStore.find(correction.getId()).orElseThrow();
        assertThat(reloadedCorrection.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(reloadedCorrection.getConfidence()).isEqualTo(0.95);
        assertThat(factStore.relationsFrom(correction.getId()))
                .anyMatch(r -> r.getToFactId().equals(existing.getId())
                        && r.getRelation() == dev.nini.memstore.domain.RelationType.SUPERSEDES);
    }

    @Test
    void r4_extractionDetectedCorrectionRetractsTarget() {
        OffsetDateTime march = OffsetDateTime.now().minusMonths(4);
        OffsetDateTime june = OffsetDateTime.now().minusMonths(1);
        UUID marchSession = sessionLog.append(null, "march",
                List.of(new SessionLogService.LogEntry("user", OLD)), march);
        extraction.extract(marchSession);

        UUID juneSession = sessionLog.append(null, "june",
                List.of(new SessionLogService.LogEntry("user",
                        "Correction: the project database uses PostgreSQL version 17")),
                june);
        ExtractionService.ExtractionResult result = extraction.extract(juneSession);
        assertThat(result.created()).isEqualTo(1);

        // The correction speech act retracts the target instead of superseding it.
        List<FactEntity> retracted = factStore.findByStatus(FactStatus.RETRACTED);
        assertThat(retracted).hasSize(1);
        assertThat(retracted.get(0).getStatement()).contains("15");

        List<FactEntity> active = factStore.findByStatus(FactStatus.ACTIVE);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatement()).contains("17");
        assertThat(active.get(0).getConfidence()).isEqualTo(0.95);
    }

    private FactCandidate candidate(String statement, UUID sessionId, UUID utteranceId,
                                    int seq) {
        return candidate(statement, sessionId, utteranceId, seq, false);
    }

    private FactCandidate candidate(String statement, UUID sessionId, UUID utteranceId,
                                    int seq, boolean explicitCorrection) {
        return new FactCandidate(statement, FactType.DECISION, 0.9,
                embeddingProvider.embed(statement), embeddingProvider.modelId(),
                List.of(utteranceId), sessionId, seq, explicitCorrection);
    }
}
