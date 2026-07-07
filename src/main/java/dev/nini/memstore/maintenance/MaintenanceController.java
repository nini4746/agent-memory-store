package dev.nini.memstore.maintenance;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manual trigger for the maintenance job (SPEC R5/R6). */
@RestController
@RequestMapping("/internal/maintenance")
public class MaintenanceController {

    private final MaintenanceJob maintenanceJob;

    public MaintenanceController(MaintenanceJob maintenanceJob) {
        this.maintenanceJob = maintenanceJob;
    }

    @PostMapping("/run")
    public Map<String, List<String>> run() {
        return Map.of("steps", maintenanceJob.runOnce());
    }
}
