package dev.nini.memstore.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Greedy score-descending budget packing (SPEC R3). A fact that does not fit is
 * skipped and the next fact is tried; if nothing fits, the result is empty and
 * carries a warning.
 */
public final class BudgetPacker {

    private BudgetPacker() {
    }

    /** An item to pack: its token cost and score. */
    public interface Weighted {
        int tokenCost();

        double score();
    }

    /** Packed output plus the estimated token total and an optional warning. */
    public record Packed<T>(List<T> items, int estimatedTokens, String warning) {
    }

    public static <T extends Weighted> Packed<T> pack(List<T> candidates,
                                                      int budgetTokens) {
        List<T> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Weighted::score).reversed());

        List<T> chosen = new ArrayList<>();
        int used = 0;
        for (T item : sorted) {
            if (used + item.tokenCost() <= budgetTokens) {
                chosen.add(item);
                used += item.tokenCost();
            }
        }

        String warning = null;
        if (chosen.isEmpty() && !candidates.isEmpty()) {
            warning = "no facts fit within budget_tokens=" + budgetTokens;
        }
        return new Packed<>(chosen, used, warning);
    }
}
