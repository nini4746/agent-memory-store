package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.domain.RelationType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.extraction.CorrectionService;
import dev.nini.memstore.extraction.PropagationService;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Plan 08 verification of propagation (SPEC R6-A1, R6-A2). */
@SpringBootTest
class PropagationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    SessionLogService sessionLog;
    @Autowired
    FactStore factStore;
    @Autowired
    CorrectionService correctionService;
    @Autowired
    PropagationService propagationService;
    @Autowired
    EmbeddingProvider embeddingProvider;

    private FactEntity fact(String statement) {
        UUID session = sessionLog.append(null, "s",
                List.of(new SessionLogService.LogEntry("user", statement)));
        UUID utteranceId = sessionLog.utterancesForSession(session).get(0).getId();
        return factStore.save(new FactStore.NewFact(statement, FactType.DECISION, 0.9,
                embeddingProvider.embed(statement), embeddingProvider.modelId(), false),
                List.of(utteranceId));
    }

    @Test
    void r6a1_correctingAFlagsRefiningB() {
        FactEntity a = fact("The API returns results as JSON");
        FactEntity b = fact("The API returns results as JSON with a data envelope");
        // B refines A.
        factStore.addRelation(b.getId(), a.getId(), RelationType.REFINES);

        correctionService.correct(a.getId(), "The API returns results as XML");

        FactEntity reloadedB = factStore.find(b.getId()).orElseThrow();
        assertThat(reloadedB.isNeedsReview()).isTrue();
    }

    @Test
    @Timeout(20)
    void r6a2_cyclicRelationsTerminate() {
        FactEntity a = fact("service alpha configuration");
        FactEntity b = fact("service alpha configuration detail");
        factStore.addRelation(a.getId(), b.getId(), RelationType.REFINES);
        factStore.addRelation(b.getId(), a.getId(), RelationType.REFINES);

        // Must terminate despite the cycle.
        propagationService.propagateFrom(a.getId());

        assertThat(factStore.find(b.getId()).orElseThrow().isNeedsReview()).isTrue();
    }
}
