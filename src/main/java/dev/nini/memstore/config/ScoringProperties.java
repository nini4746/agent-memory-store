package dev.nini.memstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retrieval scoring weights (SPEC R3). Defaults: 0.55 sim, 0.25 rec, 0.20 conf. */
@ConfigurationProperties(prefix = "memstore.scoring")
public class ScoringProperties {

    private double weightSimilarity = 0.55;
    private double weightRecency = 0.25;
    private double weightConfidence = 0.20;
    private double needsReviewPenalty = 0.5;

    public double getWeightSimilarity() {
        return weightSimilarity;
    }

    public void setWeightSimilarity(double weightSimilarity) {
        this.weightSimilarity = weightSimilarity;
    }

    public double getWeightRecency() {
        return weightRecency;
    }

    public void setWeightRecency(double weightRecency) {
        this.weightRecency = weightRecency;
    }

    public double getWeightConfidence() {
        return weightConfidence;
    }

    public void setWeightConfidence(double weightConfidence) {
        this.weightConfidence = weightConfidence;
    }

    public double getNeedsReviewPenalty() {
        return needsReviewPenalty;
    }

    public void setNeedsReviewPenalty(double needsReviewPenalty) {
        this.needsReviewPenalty = needsReviewPenalty;
    }
}
