package dev.nini.memstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Maintenance-job configuration (SPEC R6). */
@ConfigurationProperties(prefix = "memstore.maintenance")
public class MaintenanceProperties {

    /** Max needs_review facts re-evaluated per run. */
    private int reviewBatchSize = 20;
    /** Hard cap on LLM calls per maintenance run (SPEC N5). */
    private int llmCallBudget = 50;

    public int getReviewBatchSize() {
        return reviewBatchSize;
    }

    public void setReviewBatchSize(int reviewBatchSize) {
        this.reviewBatchSize = reviewBatchSize;
    }

    public int getLlmCallBudget() {
        return llmCallBudget;
    }

    public void setLlmCallBudget(int llmCallBudget) {
        this.llmCallBudget = llmCallBudget;
    }
}
