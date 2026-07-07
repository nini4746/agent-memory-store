package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nini.memstore.config.HalfLifeProperties;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.maintenance.DecayPolicy;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** Plan 07 pure-function verification (SPEC R5-A1). */
class DecayPolicyTest {

    private final DecayPolicy policy = new DecayPolicy(new HalfLifeProperties());

    @Test
    void r5a1_halfLifeElapsedHalvesConfidence() {
        // event half-life is 30 days -> after exactly 30 days, 0.5x
        assertThat(policy.effectiveConfidence(FactType.EVENT, 1.0, 30.0))
                .isCloseTo(0.5, Offset.offset(1e-9));
        // preference half-life is 180 days
        assertThat(policy.effectiveConfidence(FactType.PREFERENCE, 0.8, 180.0))
                .isCloseTo(0.4, Offset.offset(1e-9));
        // no elapsed time -> unchanged
        assertThat(policy.effectiveConfidence(FactType.DECISION, 0.9, 0.0))
                .isCloseTo(0.9, Offset.offset(1e-9));
    }
}
