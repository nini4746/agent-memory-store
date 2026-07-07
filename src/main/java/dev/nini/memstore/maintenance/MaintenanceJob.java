package dev.nini.memstore.maintenance;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the ordered maintenance steps (decay, then review) on a schedule and on
 * manual trigger (SPEC R5/R6).
 */
@Component
public class MaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJob.class);

    private final List<MaintenanceStep> steps;

    public MaintenanceJob(List<MaintenanceStep> steps) {
        List<MaintenanceStep> ordered = new ArrayList<>(steps);
        AnnotationAwareOrderComparator.sort(ordered);
        this.steps = ordered;
    }

    /** Runs all maintenance steps once; returns each step's summary. */
    public List<String> runOnce() {
        List<String> summaries = new ArrayList<>();
        for (MaintenanceStep step : steps) {
            String summary = step.execute();
            log.info("maintenance step: {}", summary);
            summaries.add(summary);
        }
        return summaries;
    }

    /** Scheduled daily run (03:00). */
    @Scheduled(cron = "${memstore.maintenance.cron:0 0 3 * * *}")
    public void scheduledRun() {
        runOnce();
    }
}
