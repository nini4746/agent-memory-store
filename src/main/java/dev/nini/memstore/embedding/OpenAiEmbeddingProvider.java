package dev.nini.memstore.embedding;

import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * OpenAI-compatible embedding provider (e.g. text-embedding-3-small). Requires a
 * real API key and network access, so it is exercised only in live runs; unit
 * and integration tests use {@link FakeEmbeddingProvider}.
 *
 * <p>Status: unverified in this environment - requires EMBEDDING_API_KEY.
 */
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private final RestClient client;
    private final String model;
    private final int dimension;

    public OpenAiEmbeddingProvider(String baseUrl, String apiKey, String model,
                                   int dimension) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        Map<String, Object> body = Map.of(
                "model", model,
                "input", text,
                "dimensions", dimension);
        Map<String, Object> response = client.post()
                .uri("/embeddings")
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("empty embedding response");
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("no embedding data in response");
        }
        List<Number> vector = (List<Number>) data.get(0).get("embedding");
        float[] result = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            result[i] = vector.get(i).floatValue();
        }
        return result;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
