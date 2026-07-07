package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.search.BudgetPacker;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Plan 05 property verification (SPEC R3-A2, N3): across arbitrary fact sizes and
 * budgets, the packed token total never exceeds the budget.
 */
class BudgetPackerPropertyTest {

    private record Item(int tokenCost, double score) implements BudgetPacker.Weighted {
    }

    @Property(tries = 100)
    void packedTotalNeverExceedsBudget(
            @ForAll @Size(max = 40) List<@IntRange(min = 1, max = 5000) Integer> costs,
            @ForAll @IntRange(min = 1, max = 8000) int budget) {
        List<Item> items = new ArrayList<>();
        for (int cost : costs) {
            items.add(new Item(cost, Math.random()));
        }
        BudgetPacker.Packed<Item> packed = BudgetPacker.pack(items, budget);

        int sum = packed.items().stream().mapToInt(Item::tokenCost).sum();
        assertThat(sum).isLessThanOrEqualTo(budget);
        assertThat(packed.estimatedTokens()).isEqualTo(sum);
    }
}
