package dev.nini.memstore.search;

import dev.nini.memstore.config.HalfLifeProperties;
import dev.nini.memstore.config.ScoringProperties;
import dev.nini.memstore.domain.FactType;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * Pure weighted scoring for retrieval (SPEC R3):
 * {@code score = 0.55*sim + 0.25*rec + 0.20*conf}. Recency decays by the
 * fact type's half-life; needs_review facts take a 0.5x confidence penalty.
 * No database or clock state is held, so it is unit-testable in isolation.
 */
@Component
public class ScoringPolicy {

    private static final double LN2 = Math.log(2.0);

    private final ScoringProperties weights;
    private final HalfLifeProperties halfLives;

    public ScoringPolicy(ScoringProperties weights, HalfLifeProperties halfLives) {
        this.weights = weights;
        this.halfLives = halfLives;
    }

    /** Recency component for a fact confirmed {@code daysElapsed} days ago. */
    public double recency(FactType type, double daysElapsed) {
        double halfLife = halfLives.forType(type);
        return Math.exp(-LN2 * daysElapsed / halfLife);
    }

    /** Full weighted score given a precomputed recency. */
    public double score(double similarity, double confidence, boolean needsReview,
                        FactType type, double daysElapsed) {
        double effectiveConfidence = needsReview
                ? confidence * weights.getNeedsReviewPenalty() : confidence;
        double rec = recency(type, daysElapsed);
        return weights.getWeightSimilarity() * similarity
                + weights.getWeightRecency() * rec
                + weights.getWeightConfidence() * effectiveConfidence;
    }

    /** Convenience over a search row using {@code lastConfirmedAt} and now. */
    public double score(ScoredFactRow row, OffsetDateTime now) {
        double daysElapsed = daysBetween(row.lastConfirmedAt(), now);
        return score(row.similarity(), row.confidence(), row.needsReview(),
                row.type(), daysElapsed);
    }

    private static double daysBetween(OffsetDateTime from, OffsetDateTime to) {
        if (from == null) {
            return 0.0;
        }
        double seconds = Duration.between(from, to).toMillis() / 1000.0;
        return Math.max(0.0, seconds / 86400.0);
    }
}
