package dev.nini.memstore.domain;

/**
 * Category of an extracted fact. Persisted as the lowercase token used across
 * the SPEC and SQL CHECK constraints.
 */
public enum FactType {
    PREFERENCE,
    CONSTRAINT,
    DECISION,
    EVENT,
    ENTITY;

    public String code() {
        return name().toLowerCase();
    }

    public static FactType fromCode(String code) {
        return FactType.valueOf(code.toUpperCase());
    }
}
