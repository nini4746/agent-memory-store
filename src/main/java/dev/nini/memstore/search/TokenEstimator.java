package dev.nini.memstore.search;

/**
 * Conservative token estimate: {@code ceil(chars / 4 * 1.1)} (SPEC R3). The 1.1
 * factor biases the estimate high so the packed result never underestimates the
 * real budget usage.
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0 * 1.1);
    }
}
