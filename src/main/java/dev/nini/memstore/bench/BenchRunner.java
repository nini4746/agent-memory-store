package dev.nini.memstore.bench;

import dev.nini.memstore.MemStoreApplication;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point for {@code ./gradlew benchRun} (SPEC R8). Boots the application
 * (no web server), runs the scenario suite, writes {@code bench-report.md}, and
 * exits non-zero if the overall pass rate is below 80%.
 *
 * <p>Uses whatever providers are configured: the deterministic fakes by default
 * (repeatable offline), or the Claude/embedding providers when keys are set.
 */
public final class BenchRunner {

    private BenchRunner() {
    }

    public static void main(String[] args) throws Exception {
        String scenarioDir = System.getProperty("bench.scenarioDir", "bench/scenarios");
        String reportPath = System.getProperty("bench.report", "bench-report.md");

        SpringApplication app = new SpringApplication(MemStoreApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx = app.run(args)) {
            BenchHarness harness = ctx.getBean(BenchHarness.class);
            double overall = harness.runAll(Path.of(scenarioDir), Path.of(reportPath));
            System.out.println("Benchmark overall pass rate: " + overall + "%");
            if (overall < 80.0) {
                System.exit(1);
            }
        }
    }
}
