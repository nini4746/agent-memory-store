-- HNSW index for cosine-distance nearest-neighbour search over fact embeddings
-- (SPEC R3 retrieval). vector_cosine_ops matches the <=> operator used by the
-- vector search layer.
CREATE INDEX idx_fact_embedding_hnsw
    ON fact USING hnsw (embedding vector_cosine_ops);
