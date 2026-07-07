package dev.nini.memstore.embedding;

/**
 * Abstraction over an embedding backend. Implementations must be deterministic
 * enough that the same text yields the same vector within a run so that stored
 * embeddings and query embeddings are comparable.
 */
public interface EmbeddingProvider {

    /** Returns the embedding vector for the given text. */
    float[] embed(String text);

    /** Stable identifier of the model, recorded on each fact. */
    String modelId();

    /** Vector dimension produced by this provider. */
    int dimension();
}
