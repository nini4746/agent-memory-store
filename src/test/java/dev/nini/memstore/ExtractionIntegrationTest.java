package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.extraction.ExtractionService;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Plan 04 verification (SPEC R2) using the deterministic fake LLM and fake
 * embeddings against a real database.
 */
@SpringBootTest
class ExtractionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    ExtractionService extraction;
    @Autowired
    FactStore factStore;

    private UUID seedSession(List<String> userLines) {
        UUID sessionId = null;
        java.util.List<SessionLogService.LogEntry> entries = new java.util.ArrayList<>();
        for (String line : userLines) {
            entries.add(new SessionLogService.LogEntry("user", line));
        }
        return sessionLog.append(sessionId, "test", entries);
    }

    @Test
    void r2a1_extractsPreferenceConstraintDecision() {
        UUID sessionId = seedSession(List.of(
                "I prefer spaces over tabs for indentation",
                "The service must never store plaintext passwords",
                "We decided to use PostgreSQL 15 for the database"));

        ExtractionService.ExtractionResult result = extraction.extract(sessionId);
        assertThat(result.created()).isEqualTo(3);

        List<FactEntity> active = factStore.findByStatus(FactStatus.ACTIVE);
        assertThat(active).extracting(FactEntity::getType)
                .contains(FactType.PREFERENCE, FactType.CONSTRAINT, FactType.DECISION);
    }

    @Test
    void r2a2_everyFactHasProvenance() {
        UUID sessionId = seedSession(List.of(
                "We decided to deploy on AWS",
                "I prefer dark mode"));
        extraction.extract(sessionId);

        for (FactEntity fact : factStore.findByStatus(FactStatus.ACTIVE)) {
            assertThat(factStore.provenanceUtterances(fact.getId())).isNotEmpty();
        }
    }

    @Test
    void r2a3_chitchatYieldsNothing() {
        UUID sessionId = seedSession(List.of(
                "hello there",
                "thanks, how is the weather today",
                "lol ok bye"));
        ExtractionService.ExtractionResult result = extraction.extract(sessionId);

        assertThat(result.created()).isZero();
        assertThat(result.confirmed()).isZero();
        assertThat(factStore.findByStatus(FactStatus.ACTIVE)).isEmpty();
    }

    @Test
    void r2a4_reExtractionConfirmsInsteadOfDuplicating() {
        UUID sessionId = seedSession(List.of(
                "We decided to use PostgreSQL 15 for the database"));
        extraction.extract(sessionId);

        List<FactEntity> afterFirst = factStore.findByStatus(FactStatus.ACTIVE);
        assertThat(afterFirst).hasSize(1);
        UUID factId = afterFirst.get(0).getId();
        double confidenceBefore = afterFirst.get(0).getConfidence();
        var confirmedBefore = afterFirst.get(0).getLastConfirmedAt();

        // Re-extract the same session: should confirm, not create a new fact.
        ExtractionService.ExtractionResult second = extraction.extract(sessionId);
        assertThat(second.created()).isZero();
        assertThat(second.confirmed()).isEqualTo(1);

        List<FactEntity> afterSecond = factStore.findByStatus(FactStatus.ACTIVE);
        assertThat(afterSecond).hasSize(1);
        FactEntity reloaded = factStore.find(factId).orElseThrow();
        assertThat(reloaded.getConfidence()).isGreaterThan(confidenceBefore);
        assertThat(reloaded.getLastConfirmedAt()).isAfterOrEqualTo(confirmedBefore);
    }
}
