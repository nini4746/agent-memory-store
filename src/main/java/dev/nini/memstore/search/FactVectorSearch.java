package dev.nini.memstore.search;

import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.service.FactStore;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * pgvector cosine nearest-neighbour search over facts (SPEC R3 base). Only facts
 * embedded with the current provider's model are considered, which is how the
 * store refuses to mix embedding models (SPEC embedding-dimension edge).
 */
@Component
public class FactVectorSearch {

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embeddingProvider;

    public FactVectorSearch(JdbcTemplate jdbc, EmbeddingProvider embeddingProvider) {
        this.jdbc = jdbc;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Returns the top-k facts nearest to {@code queryEmbedding}, restricted to
     * the given statuses and to the active embedding model. Similarity is
     * normalized to [0,1] via {@code (1 + cosine) / 2}.
     */
    public List<ScoredFactRow> topK(float[] queryEmbedding, int k,
                                    List<FactStatus> statusFilter) {
        if (statusFilter == null || statusFilter.isEmpty()) {
            return List.of();
        }
        String vectorLiteral = FactStore.toVectorLiteral(queryEmbedding);
        String placeholders = statusFilter.stream()
                .map(s -> "?")
                .collect(Collectors.joining(","));

        String sql = "SELECT id, statement, type, confidence, status, needs_review, "
                + "last_confirmed_at, "
                + "1 - (embedding <=> CAST(? AS vector)) / 2 AS sim "
                + "FROM fact "
                + "WHERE embedding IS NOT NULL "
                + "AND embedding_model = ? "
                + "AND status IN (" + placeholders + ") "
                + "ORDER BY embedding <=> CAST(? AS vector) "
                + "LIMIT ?";

        List<Object> args = new ArrayList<>();
        args.add(vectorLiteral);
        args.add(embeddingProvider.modelId());
        for (FactStatus status : statusFilter) {
            args.add(status.code());
        }
        args.add(vectorLiteral);
        args.add(k);

        return jdbc.query(sql, ROW_MAPPER, args.toArray());
    }

    private static final RowMapper<ScoredFactRow> ROW_MAPPER = (rs, rowNum) ->
            new ScoredFactRow(
                    rs.getObject("id", java.util.UUID.class),
                    rs.getString("statement"),
                    FactType.fromCode(rs.getString("type")),
                    rs.getDouble("confidence"),
                    FactStatus.fromCode(rs.getString("status")),
                    rs.getBoolean("needs_review"),
                    rs.getObject("last_confirmed_at", java.time.OffsetDateTime.class),
                    rs.getDouble("sim"));
}
