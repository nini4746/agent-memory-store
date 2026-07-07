package dev.nini.memstore.domain;

/**
 * Lifecycle state of a fact. None of the non-active states delete the row;
 * superseded/retracted/expired are all soft states with the row preserved.
 */
public enum FactStatus {
    ACTIVE,
    SUPERSEDED,
    RETRACTED,
    EXPIRED;

    public String code() {
        return name().toLowerCase();
    }

    public static FactStatus fromCode(String code) {
        return FactStatus.valueOf(code.toUpperCase());
    }
}
