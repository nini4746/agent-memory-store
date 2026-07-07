package dev.nini.memstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Decay configuration (SPEC R5). */
@ConfigurationProperties(prefix = "memstore.decay")
public class DecayProperties {

    /** Effective-confidence threshold below which a fact becomes expired. */
    private double expireThreshold = 0.15;

    public double getExpireThreshold() {
        return expireThreshold;
    }

    public void setExpireThreshold(double expireThreshold) {
        this.expireThreshold = expireThreshold;
    }
}
