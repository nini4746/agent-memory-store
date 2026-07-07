package dev.nini.memstore.maintenance;

import dev.nini.memstore.config.MaintenanceProperties;
import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.extraction.PropagationService;
import dev.nini.memstore.llm.LlmClient;
import dev.nini.memstore.repo.FactRepository;
import dev.nini.memstore.service.FactStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-evaluation step (SPEC R6). Loads the oldest needs_review facts (bounded by
 * the batch size), asks the LLM whether each should be kept, superseded, or
 * retracted against the current active set, and applies the result. All LLM
 * calls go through a per-run {@link LlmCallBudget} so the run never exceeds the
 * call cap (SPEC N5). Unprocessed facts keep their flag for the next run.
 */
@Order(2)
@Component
public class ReviewStep implements MaintenanceStep {

    private static final int ACTIVE_CONTEXT_CAP = 50;

    private final FactRepository factRepository;
    private final FactStore factStore;
    private final LlmClient llm;
    private final PropagationService propagation;
    private final MaintenanceProperties properties;
    private final AtomicInteger lastLlmCalls = new AtomicInteger();

    public ReviewStep(FactRepository factRepository, FactStore factStore, LlmClient llm,
                      PropagationService propagation, MaintenanceProperties properties) {
        this.factRepository = factRepository;
        this.factStore = factStore;
        this.llm = llm;
        this.propagation = propagation;
        this.properties = properties;
    }

    /** LLM calls made during the most recent run (for verification, SPEC R6-A4). */
    public int lastLlmCalls() {
        return lastLlmCalls.get();
    }

    @Override
    @Transactional
    public String execute() {
        LlmCallBudget budget = new LlmCallBudget(properties.getLlmCallBudget());
        List<FactEntity> flagged = factRepository.findByNeedsReviewTrueOrderByCreatedAtAsc(
                Limit.of(properties.getReviewBatchSize()));
        List<String> activeContext = factStore.findByStatus(FactStatus.ACTIVE).stream()
                .map(FactEntity::getStatement)
                .limit(ACTIVE_CONTEXT_CAP)
                .toList();

        int kept = 0;
        int superseded = 0;
        int retracted = 0;
        for (FactEntity fact : flagged) {
            if (fact.getStatus() != FactStatus.ACTIVE) {
                factStore.setNeedsReview(fact.getId(), false);
                continue;
            }
            if (!budget.hasBudget()) {
                break;
            }
            LlmClient.ReviewJudgment judgment;
            try {
                judgment = budget.call(() -> llm.reviewFact(fact.getStatement(), activeContext));
            } catch (RuntimeException e) {
                // Parsing/LLM failure: keep the flag for the next run (safe side).
                continue;
            }
            switch (judgment) {
                case KEEP -> {
                    factStore.setNeedsReview(fact.getId(), false);
                    kept++;
                }
                case SUPERSEDE -> {
                    factStore.transition(fact.getId(), FactStatus.SUPERSEDED, null);
                    factStore.setNeedsReview(fact.getId(), false);
                    propagation.propagateFrom(fact.getId());
                    superseded++;
                }
                case RETRACT -> {
                    factStore.transition(fact.getId(), FactStatus.RETRACTED, null);
                    factStore.setNeedsReview(fact.getId(), false);
                    propagation.propagateFrom(fact.getId());
                    retracted++;
                }
            }
        }
        lastLlmCalls.set(budget.used());
        return "review: llmCalls=" + budget.used() + " kept=" + kept
                + " superseded=" + superseded + " retracted=" + retracted;
    }
}
