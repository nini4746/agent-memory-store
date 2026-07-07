package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.domain.UtteranceEntity;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan 02 verification (SPEC R1, N1) against a real PostgreSQL + pgvector.
 * Skipped automatically when Docker is unavailable.
 */
@SpringBootTest
class StorageIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    FactStore factStore;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void r1a1_appendedContentIsByteIdenticalOnReRead() {
        String tricky = "line1\nline2\ttab \"quotes\" 이모지 😀🚀 \\backslash '단일따옴표'";
        UUID sessionId = sessionLog.append(null, "test",
                List.of(new SessionLogService.LogEntry("user", tricky)));

        List<UtteranceEntity> reread = sessionLog.utterancesForSession(sessionId);
        assertThat(reread).hasSize(1);
        assertThat(reread.get(0).getContent()).isEqualTo(tricky);
    }

    @Test
    void r1a2_utteranceCountDoesNotDecreaseAfterTransition() {
        UUID sessionId = sessionLog.append(null, "test", List.of(
                new SessionLogService.LogEntry("user", "I use PostgreSQL 15"),
                new SessionLogService.LogEntry("assistant", "Noted")));
        List<UtteranceEntity> utts = sessionLog.utterancesForSession(sessionId);
        long before = jdbc.queryForObject("SELECT count(*) FROM utterance", Long.class);

        FactEntity fact = factStore.save(
                new FactStore.NewFact("Uses PostgreSQL 15", FactType.DECISION, 0.9,
                        null, null, false),
                List.of(utts.get(0).getId()));
        factStore.transition(fact.getId(), FactStatus.SUPERSEDED, null);

        long after = jdbc.queryForObject("SELECT count(*) FROM utterance", Long.class);
        assertThat(after).isGreaterThanOrEqualTo(before);
    }

    @Test
    void r1a3_statusTransitionRecordsFields() {
        UUID sessionId = sessionLog.append(null, "test",
                List.of(new SessionLogService.LogEntry("user", "old fact")));
        UUID utteranceId = sessionLog.utterancesForSession(sessionId).get(0).getId();

        FactEntity winner = factStore.save(
                new FactStore.NewFact("new fact", FactType.DECISION, 0.9, null, null, false),
                List.of(utteranceId));
        FactEntity loser = factStore.save(
                new FactStore.NewFact("old fact", FactType.DECISION, 0.9, null, null, false),
                List.of(utteranceId));

        factStore.transition(loser.getId(), FactStatus.SUPERSEDED, winner.getId());

        FactEntity reloaded = factStore.find(loser.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FactStatus.SUPERSEDED);
        assertThat(reloaded.getSupersededBy()).isEqualTo(winner.getId());
        assertThat(reloaded.getInvalidatedAt()).isNotNull();
        assertThat(reloaded.getConfidence()).isEqualTo(0.9);
        assertThat(reloaded.getLastConfirmedAt()).isNotNull();
    }

    @Test
    void r1a4_provenanceReverseLookupWorks() {
        UUID sessionId = sessionLog.append(null, "test",
                List.of(new SessionLogService.LogEntry("user", "source utterance")));
        UUID utteranceId = sessionLog.utterancesForSession(sessionId).get(0).getId();

        FactEntity fact = factStore.save(
                new FactStore.NewFact("a fact", FactType.EVENT, 0.6, null, null, false),
                List.of(utteranceId));

        List<UtteranceEntity> sources = factStore.provenanceUtterances(fact.getId());
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getId()).isEqualTo(utteranceId);
        assertThat(sources.get(0).getContent()).isEqualTo("source utterance");
    }

    @Test
    void n4_factWithoutProvenanceIsRejected() {
        assertThatThrownBy(() -> factStore.save(
                new FactStore.NewFact("orphan", FactType.EVENT, 0.6, null, null, false),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void n1_utteranceUpdateAndDeleteBlockedByTrigger() {
        UUID sessionId = sessionLog.append(null, "test",
                List.of(new SessionLogService.LogEntry("user", "immutable")));
        UUID utteranceId = sessionLog.utterancesForSession(sessionId).get(0).getId();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE utterance SET content = 'hacked' WHERE id = ?", utteranceId))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM utterance WHERE id = ?", utteranceId))
                .hasMessageContaining("immutable");
    }
}
