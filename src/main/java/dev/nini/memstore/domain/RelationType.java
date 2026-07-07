package dev.nini.memstore.domain;

/**
 * Relationship between two facts.
 */
public enum RelationType {
    CONTRADICTS,
    SUPERSEDES,
    REFINES,
    DUPLICATES;

    public String code() {
        return name().toLowerCase();
    }

    public static RelationType fromCode(String code) {
        return RelationType.valueOf(code.toUpperCase());
    }
}
