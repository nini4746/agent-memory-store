package dev.nini.memstore.maintenance;

/**
 * One stage of the maintenance job (SPEC R5/R6). Steps are ordered Spring beans
 * executed in sequence by {@link MaintenanceJob}.
 */
public interface MaintenanceStep {

    /** Executes the step, returning a short human-readable summary. */
    String execute();
}
