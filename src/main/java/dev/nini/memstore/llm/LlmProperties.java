package dev.nini.memstore.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code memstore.llm.*} configuration. */
@ConfigurationProperties(prefix = "memstore.llm")
public class LlmProperties {

    /** Provider selector: {@code fake} (deterministic) or {@code claude}. */
    private String provider = "fake";
    private String apiKey = "";
    private String model = "claude-opus-4-8";
    private String baseUrl = "https://api.anthropic.com";

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
