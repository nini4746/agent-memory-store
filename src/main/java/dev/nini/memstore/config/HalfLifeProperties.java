package dev.nini.memstore.config;

import dev.nini.memstore.domain.FactType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-specific half-lives in days (SPEC R5). Shared by retrieval recency
 * (Plan 05) and decay (Plan 07) so the constants live in one place.
 */
@ConfigurationProperties(prefix = "memstore.half-life-days")
public class HalfLifeProperties {

    private int preference = 180;
    private int constraint = 180;
    private int decision = 90;
    private int event = 30;
    private int entity = 365;

    public int forType(FactType type) {
        return switch (type) {
            case PREFERENCE -> preference;
            case CONSTRAINT -> constraint;
            case DECISION -> decision;
            case EVENT -> event;
            case ENTITY -> entity;
        };
    }

    public int getPreference() {
        return preference;
    }

    public void setPreference(int preference) {
        this.preference = preference;
    }

    public int getConstraint() {
        return constraint;
    }

    public void setConstraint(int constraint) {
        this.constraint = constraint;
    }

    public int getDecision() {
        return decision;
    }

    public void setDecision(int decision) {
        this.decision = decision;
    }

    public int getEvent() {
        return event;
    }

    public void setEvent(int event) {
        this.event = event;
    }

    public int getEntity() {
        return entity;
    }

    public void setEntity(int entity) {
        this.entity = entity;
    }
}
