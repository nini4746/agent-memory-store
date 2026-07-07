package dev.nini.memstore.maintenance;

import dev.nini.memstore.config.DecayProperties;
import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.service.FactStore;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decay/soft-forgetting step (SPEC R5). Scans active facts and transitions any
 * whose effective confidence has dropped below the threshold to {@code expired}
 * (row preserved, not deleted).
 */
@Order(1)
@Component
public class DecayStep implements MaintenanceStep {

    private final FactStore factStore;
    private final DecayPolicy decayPolicy;
    private final DecayProperties decayProperties;
    private final Clock clock;

    public DecayStep(FactStore factStore, DecayPolicy decayPolicy,
                     DecayProperties decayProperties, Clock clock) {
        this.factStore = factStore;
        this.decayPolicy = decayPolicy;
        this.decayProperties = decayProperties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public String execute() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<FactEntity> active = factStore.findByStatus(FactStatus.ACTIVE);
        int expired = 0;
        for (FactEntity fact : active) {
            double daysElapsed = daysBetween(fact.getLastConfirmedAt(), now);
            double effective = decayPolicy.effectiveConfidence(
                    fact.getType(), fact.getConfidence(), daysElapsed);
            if (effective < decayProperties.getExpireThreshold()) {
                factStore.transition(fact.getId(), FactStatus.EXPIRED, null);
                expired++;
            }
        }
        return "decay: scanned " + active.size() + ", expired " + expired;
    }

    private static double daysBetween(OffsetDateTime from, OffsetDateTime to) {
        if (from == null) {
            return 0.0;
        }
        double seconds = Duration.between(from, to).toMillis() / 1000.0;
        return Math.max(0.0, seconds / 86400.0);
    }
}
