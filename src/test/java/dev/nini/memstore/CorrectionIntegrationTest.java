package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.extraction.CorrectionService;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Plan 06 verification of the explicit correction path (SPEC R4). */
@SpringBootTest
class CorrectionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    FactStore factStore;
    @Autowired
    CorrectionService correctionService;
    @Autowired
    EmbeddingProvider embeddingProvider;

    @Test
    void correctRetractsTargetAndStoresHighConfidenceCorrection() {
        UUID session = sessionLog.append(null, "s",
                List.of(new SessionLogService.LogEntry("user", "seed")));
        UUID utteranceId = sessionLog.utterancesForSession(session).get(0).getId();
        FactEntity target = factStore.save(new FactStore.NewFact(
                "Deploy target is AWS", FactType.DECISION, 0.9,
                embeddingProvider.embed("Deploy target is AWS"),
                embeddingProvider.modelId(), false), List.of(utteranceId));

        FactEntity correction = correctionService.correct(target.getId(),
                "Deploy target is GCP");

        FactEntity reloadedTarget = factStore.find(target.getId()).orElseThrow();
        assertThat(reloadedTarget.getStatus()).isEqualTo(FactStatus.RETRACTED);
        assertThat(reloadedTarget.getInvalidatedAt()).isNotNull();
        assertThat(reloadedTarget.getSupersededBy()).isEqualTo(correction.getId());

        assertThat(correction.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(correction.getConfidence()).isEqualTo(0.95);
        // Correction fact has synthetic provenance (N4).
        assertThat(factStore.provenanceUtterances(correction.getId())).isNotEmpty();
    }
}
