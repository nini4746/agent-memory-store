package dev.nini.memstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Context-budget bounds (SPEC R3). */
@ConfigurationProperties(prefix = "memstore.budget")
public class BudgetProperties {

    private int defaultTokens = 2000;
    private int maxTokens = 8000;

    public int getDefaultTokens() {
        return defaultTokens;
    }

    public void setDefaultTokens(int defaultTokens) {
        this.defaultTokens = defaultTokens;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }
}
