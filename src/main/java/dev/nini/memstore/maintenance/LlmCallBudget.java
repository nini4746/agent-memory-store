package dev.nini.memstore.maintenance;

import java.util.function.Supplier;

/**
 * Per-run LLM call budget (SPEC N5/R6-A4). Once the cap is reached, further calls
 * are refused so a single maintenance run cannot make unbounded LLM calls.
 */
public class LlmCallBudget {

    private final int max;
    private int used;

    public LlmCallBudget(int max) {
        this.max = max;
    }

    public boolean hasBudget() {
        return used < max;
    }

    /** Runs the call if budget remains, incrementing the counter. */
    public <T> T call(Supplier<T> call) {
        if (used >= max) {
            throw new IllegalStateException("LLM call budget exhausted (" + max + ")");
        }
        used++;
        return call.get();
    }

    public int used() {
        return used;
    }
}
