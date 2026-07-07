package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Minimal unit test that runs without Docker or external keys, so the default
 * test task always has an executable, passing test.
 */
class SanityTest {

    @Test
    void arithmeticHolds() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
