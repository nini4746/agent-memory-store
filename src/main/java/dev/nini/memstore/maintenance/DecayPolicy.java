package dev.nini.memstore.maintenance;

import dev.nini.memstore.config.HalfLifeProperties;
import dev.nini.memstore.domain.FactType;
import org.springframework.stereotype.Component;

/**
 * Pure decay computation (SPEC R5):
 * {@code effective = confidence * exp(-ln2 * daysElapsed / halfLife)} using the
 * same type half-lives as retrieval recency.
 */
@Component
public class DecayPolicy {

    private static final double LN2 = Math.log(2.0);

    private final HalfLifeProperties halfLives;

    public DecayPolicy(HalfLifeProperties halfLives) {
        this.halfLives = halfLives;
    }

    public double effectiveConfidence(FactType type, double confidence,
                                      double daysElapsed) {
        double halfLife = halfLives.forType(type);
        return confidence * Math.exp(-LN2 * daysElapsed / halfLife);
    }
}
