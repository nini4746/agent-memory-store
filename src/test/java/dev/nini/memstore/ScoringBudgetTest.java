package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.config.HalfLifeProperties;
import dev.nini.memstore.config.ScoringProperties;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.search.BudgetPacker;
import dev.nini.memstore.search.ScoringPolicy;
import dev.nini.memstore.search.TokenEstimator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 05 pure-function verification (SPEC R3-A1, R3-A3), no database. */
class ScoringBudgetTest {

    private final ScoringPolicy policy =
            new ScoringPolicy(new ScoringProperties(), new HalfLifeProperties());

    @Test
    void r3a1_scoreFormulaMatchesExpectedValues() {
        // Perfect similarity, full confidence, no decay -> 0.55 + 0.25 + 0.20 = 1.0
        assertThat(policy.score(1.0, 1.0, false, FactType.PREFERENCE, 0.0))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));

        // One half-life elapsed (preference = 180 days) -> recency 0.5
        // 0.55*1 + 0.25*0.5 + 0.20*1 = 0.875
        assertThat(policy.score(1.0, 1.0, false, FactType.PREFERENCE, 180.0))
                .isCloseTo(0.875, org.assertj.core.data.Offset.offset(1e-9));

        // needs_review halves confidence: 0.55 + 0.25 + 0.20*0.5 = 0.90
        assertThat(policy.score(1.0, 1.0, true, FactType.PREFERENCE, 0.0))
                .isCloseTo(0.90, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void r3a1_expectedRanking() {
        double strong = policy.score(0.9, 0.9, false, FactType.DECISION, 1.0);
        double weak = policy.score(0.3, 0.5, false, FactType.EVENT, 60.0);
        assertThat(strong).isGreaterThan(weak);
    }

    @Test
    void tokenEstimatorIsConservative() {
        // 40 chars -> ceil(40/4 * 1.1) = ceil(11.0) = 11
        assertThat(TokenEstimator.estimate("a".repeat(40))).isEqualTo(11);
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    private record Item(int tokenCost, double score) implements BudgetPacker.Weighted {
    }

    @Test
    void r3a3_oversizedFactSkippedAndNextFilled() {
        List<Item> items = List.of(
                new Item(5000, 0.95),  // oversized: skipped
                new Item(100, 0.90),   // fits
                new Item(120, 0.80));  // fits
        BudgetPacker.Packed<Item> packed = BudgetPacker.pack(items, 2000);

        assertThat(packed.items()).containsExactly(items.get(1), items.get(2));
        assertThat(packed.estimatedTokens()).isEqualTo(220);
        assertThat(packed.warning()).isNull();
    }

    @Test
    void r3a3_nothingFitsReturnsEmptyWithWarning() {
        List<Item> items = List.of(new Item(9000, 0.95), new Item(8500, 0.90));
        BudgetPacker.Packed<Item> packed = BudgetPacker.pack(items, 2000);

        assertThat(packed.items()).isEmpty();
        assertThat(packed.estimatedTokens()).isZero();
        assertThat(packed.warning()).contains("budget_tokens=2000");
    }
}
