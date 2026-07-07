package dev.nini.memstore.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code memstore.embedding.*} configuration. */
@ConfigurationProperties(prefix = "memstore.embedding")
public class EmbeddingProperties {

    /** Provider selector: {@code fake} (deterministic) or {@code openai}. */
    private String provider = "fake";
    private String apiKey = "";
    private String model = "fake-embed-1536";
    private int dimension = 1536;
    private String baseUrl = "https://api.openai.com/v1";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
