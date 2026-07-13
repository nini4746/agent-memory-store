package dev.nini.memstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conflict-detection candidate window (SPEC R4). When a new fact is resolved,
 * only the {@code candidateLimit} nearest active facts with normalized cosine
 * similarity {@code >= candidateThreshold} are considered as conflict
 * candidates. A contradiction that falls outside this window is not seen by the
 * resolver, so widening these values trades recall for extra LLM classification
 * calls. Defaults preserve the historical behaviour (top-5, 0.80).
 */
@ConfigurationProperties(prefix = "memstore.conflict")
public class ConflictProperties {

    /** Nearest active facts fetched as conflict candidates. */
    private int candidateLimit = 5;
    /** Minimum normalized cosine similarity for a neighbour to be a candidate. */
    private double candidateThreshold = 0.80;

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public double getCandidateThreshold() {
        return candidateThreshold;
    }

    public void setCandidateThreshold(double candidateThreshold) {
        this.candidateThreshold = candidateThreshold;
    }
}
