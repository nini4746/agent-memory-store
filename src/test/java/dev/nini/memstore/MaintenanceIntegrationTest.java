package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.maintenance.MaintenanceJob;
import dev.nini.memstore.search.MemorySearchService;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Plan 07 verification (SPEC R5-A2..A4). */
@SpringBootTest
class MaintenanceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    FactStore factStore;
    @Autowired
    MaintenanceJob maintenanceJob;
    @Autowired
    MemorySearchService search;
    @Autowired
    EmbeddingProvider embeddingProvider;
    @Autowired
    JdbcTemplate jdbc;

    private FactEntity seedFact(String statement, FactType type) {
        UUID sessionId = sessionLog.append(null, "test",
                List.of(new SessionLogService.LogEntry("user", statement)));
        UUID utteranceId = sessionLog.utterancesForSession(sessionId).get(0).getId();
        return factStore.save(new FactStore.NewFact(statement, type, 0.9,
                embeddingProvider.embed(statement), embeddingProvider.modelId(), false),
                List.of(utteranceId));
    }

    @Test
    void r5a2_a3_agedEventExpiresAndIsPreservedForInclusiveSearch() {
        FactEntity fact = seedFact("the build was released on friday", FactType.EVENT);
        // Age last_confirmed_at well past the event half-life (30 days).
        jdbc.update("UPDATE fact SET last_confirmed_at = ? WHERE id = ?",
                OffsetDateTime.now().minusDays(400), fact.getId());

        maintenanceJob.runOnce();

        FactEntity reloaded = factStore.find(fact.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FactStatus.EXPIRED);
        assertThat(reloaded.getInvalidatedAt()).isNotNull();

        // R5-A3: row preserved and retrievable via include_inactive.
        assertThat(factStore.find(fact.getId())).isPresent();
        var inclusive = search.search("build released friday", 2000, null, true);
        assertThat(inclusive.hits()).extracting(MemorySearchService.MemoryHit::factId)
                .contains(fact.getId());
        var activeOnly = search.search("build released friday", 2000, null, false);
        assertThat(activeOnly.hits()).extracting(MemorySearchService.MemoryHit::factId)
                .doesNotContain(fact.getId());
    }

    @Test
    void r5a4_searchDoesNotUpdateLastConfirmedAt() {
        FactEntity fact = seedFact("prefers vim keybindings", FactType.PREFERENCE);
        OffsetDateTime before = factStore.find(fact.getId()).orElseThrow()
                .getLastConfirmedAt();

        search.search("vim keybindings preference", 2000, null, false);

        OffsetDateTime after = factStore.find(fact.getId()).orElseThrow()
                .getLastConfirmedAt();
        assertThat(after).isEqualTo(before);
    }
}
