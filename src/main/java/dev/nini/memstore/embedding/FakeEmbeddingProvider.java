package dev.nini.memstore.embedding;

import java.util.Random;

/**
 * Deterministic, dependency-free embedding provider for tests and local runs.
 *
 * <p>Each token contributes a pseudo-random unit vector seeded by a stable hash
 * of the token; the token vectors are summed and L2-normalized. Texts that
 * share tokens therefore produce vectors with high cosine similarity, while
 * unrelated texts point in largely independent directions. This is enough to
 * exercise the similarity-ranking and dedupe logic without any network calls.
 */
public class FakeEmbeddingProvider implements EmbeddingProvider {

    private final int dimension;
    private final String modelId;

    public FakeEmbeddingProvider(int dimension, String modelId) {
        this.dimension = dimension;
        this.modelId = modelId;
    }

    @Override
    public float[] embed(String text) {
        double[] acc = new double[dimension];
        String[] tokens = tokenize(text);
        for (String token : tokens) {
            long seed = stableHash(token);
            Random random = new Random(seed);
            // A sparse-ish contribution keeps unrelated tokens near-orthogonal
            // while shared tokens dominate the resulting direction.
            for (int i = 0; i < dimension; i++) {
                acc[i] += random.nextGaussian();
            }
        }
        return normalize(acc);
    }

    private static String[] tokenize(String text) {
        String cleaned = text == null ? "" : text.toLowerCase();
        String[] tokens = cleaned.split("[^\\p{L}\\p{Nd}]+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
            return new String[]{"__empty__"};
        }
        return tokens;
    }

    private static long stableHash(String token) {
        // FNV-1a 64-bit for stability across JVMs (String.hashCode is stable too,
        // but this avoids any dependence on int hashing quirks).
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private float[] normalize(double[] acc) {
        double norm = 0.0;
        for (double v : acc) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        float[] result = new float[dimension];
        if (norm == 0.0) {
            return result;
        }
        for (int i = 0; i < dimension; i++) {
            result[i] = (float) (acc[i] / norm);
        }
        return result;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
