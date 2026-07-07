package dev.nini.memstore.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.nini.memstore.embedding.EmbeddingProperties;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.llm.LlmProperties;
import dev.nini.memstore.maintenance.MaintenanceJob;
import dev.nini.memstore.mcp.McpToolService;
import dev.nini.memstore.service.SessionLogService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs the MemFail-style benchmark suite (SPEC R8): seeds each scenario via the
 * service/MCP layer, applies triggers (extract/age/correct/maintenance), queries,
 * judges deterministically, and writes {@code bench-report.md}.
 */
@Component
public class BenchHarness {

    private static final Logger log = LoggerFactory.getLogger(BenchHarness.class);
    private static final List<String> CATEGORY_ORDER =
            List.of("contradiction", "correction", "decay", "provenance", "budget");

    private final SessionLogService sessionLog;
    private final McpToolService tools;
    private final MaintenanceJob maintenanceJob;
    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingProperties embeddingProperties;
    private final LlmProperties llmProperties;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public BenchHarness(SessionLogService sessionLog, McpToolService tools,
                        MaintenanceJob maintenanceJob, JdbcTemplate jdbc,
                        EmbeddingProvider embeddingProvider,
                        EmbeddingProperties embeddingProperties,
                        LlmProperties llmProperties) {
        this.sessionLog = sessionLog;
        this.tools = tools;
        this.maintenanceJob = maintenanceJob;
        this.jdbc = jdbc;
        this.embeddingProvider = embeddingProvider;
        this.embeddingProperties = embeddingProperties;
        this.llmProperties = llmProperties;
        this.yaml.setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        this.yaml.configure(
                com.fasterxml.jackson.databind.DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private record Outcome(BenchScenario scenario, boolean passed, String reason) {
    }

    public double runAll(Path scenarioDir, Path reportPath) throws IOException {
        List<BenchScenario> scenarios = loadScenarios(scenarioDir);
        log.info("loaded {} benchmark scenarios", scenarios.size());
        List<Outcome> outcomes = new ArrayList<>();
        for (BenchScenario scenario : scenarios) {
            resetDatabase();
            Outcome outcome;
            try {
                String reason = runScenario(scenario);
                outcome = new Outcome(scenario, reason == null, reason);
            } catch (RuntimeException e) {
                outcome = new Outcome(scenario, false, "exception: " + e.getMessage());
            }
            outcomes.add(outcome);
        }
        return writeReport(reportPath, scenarios, outcomes);
    }

    private List<BenchScenario> loadScenarios(Path dir) throws IOException {
        List<BenchScenario> all = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> yamlFiles = files
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
            for (Path file : yamlFiles) {
                BenchScenario[] parsed = yaml.readValue(file.toFile(), BenchScenario[].class);
                for (BenchScenario s : parsed) {
                    all.add(s);
                }
            }
        }
        return all;
    }

    /** Executes a scenario; returns null on pass, or a failure reason. */
    private String runScenario(BenchScenario s) {
        if (s.sessions != null) {
            for (BenchScenario.Session session : s.sessions) {
                List<SessionLogService.LogEntry> entries = new ArrayList<>();
                for (BenchScenario.Utterance u : session.utterances) {
                    entries.add(new SessionLogService.LogEntry(u.role, u.content));
                }
                var sessionId = sessionLog.append(null, session.source, entries,
                        OffsetDateTime.now().plusDays(session.offsetDays));
                if (s.extract) {
                    tools.extract(objArg("session_id", sessionId.toString()));
                }
            }
        }
        if (s.age != null) {
            for (BenchScenario.AgeOp op : s.age) {
                jdbc.update("UPDATE fact SET last_confirmed_at = ? WHERE statement LIKE ?",
                        OffsetDateTime.now().minusDays(op.days), "%" + op.contains + "%");
            }
        }
        if (s.correct != null) {
            for (BenchScenario.CorrectOp op : s.correct) {
                List<String> ids = jdbc.queryForList(
                        "SELECT id::text FROM fact WHERE status = 'active' AND statement LIKE ? "
                                + "LIMIT 1", String.class, "%" + op.targetContains + "%");
                if (ids.isEmpty()) {
                    return "correct target not found: " + op.targetContains;
                }
                ObjectNode args = json.createObjectNode();
                args.put("fact_id", ids.get(0));
                args.put("correction_statement", op.statement);
                tools.correct(args);
            }
        }
        if (s.maintenance) {
            maintenanceJob.runOnce();
        }
        return judge(s);
    }

    @SuppressWarnings("unchecked")
    private String judge(BenchScenario s) {
        BenchScenario.Expect e = s.expect;
        if (e == null) {
            return null;
        }
        Map<String, Object> active = tools.search(searchArgs(s, false));
        List<Map<String, Object>> hits = (List<Map<String, Object>>) active.get("hits");
        List<String> statements = hits.stream()
                .map(h -> String.valueOf(h.get("statement"))).toList();

        if (e.containsAll != null) {
            for (String token : e.containsAll) {
                if (statements.stream().noneMatch(st -> st.contains(token))) {
                    return "expected active hits to contain '" + token + "', got " + statements;
                }
            }
        }
        if (e.excludes != null) {
            for (String token : e.excludes) {
                if (statements.stream().anyMatch(st -> st.contains(token))) {
                    return "expected active hits to exclude '" + token + "', got " + statements;
                }
            }
        }
        if (e.activeHitCount != null && hits.size() != e.activeHitCount) {
            return "expected " + e.activeHitCount + " active hits, got " + hits.size();
        }
        if (e.activeHitCountLt != null && hits.size() >= e.activeHitCountLt) {
            return "expected fewer than " + e.activeHitCountLt + " active hits, got " + hits.size();
        }
        if (Boolean.TRUE.equals(e.tokensWithinBudget)) {
            int budget = s.query.budgetTokens == null ? 2000 : s.query.budgetTokens;
            int used = ((Number) active.get("estimated_tokens")).intValue();
            if (used > budget) {
                return "estimated_tokens " + used + " exceeds budget " + budget;
            }
        }
        if (e.inactiveContains != null) {
            Map<String, Object> inclusive = tools.search(searchArgs(s, true));
            List<Map<String, Object>> ihits = (List<Map<String, Object>>) inclusive.get("hits");
            boolean found = ihits.stream()
                    .anyMatch(h -> String.valueOf(h.get("statement")).contains(e.inactiveContains));
            if (!found) {
                return "expected inactive search to contain '" + e.inactiveContains + "'";
            }
        }
        if (e.inspectProvenanceContains != null) {
            if (hits.isEmpty()) {
                return "no active hit to inspect provenance for";
            }
            String factId = String.valueOf(hits.get(0).get("fact_id"));
            Map<String, Object> inspected = tools.inspect(objArg("fact_id", factId));
            List<Map<String, Object>> prov =
                    (List<Map<String, Object>>) inspected.get("provenance");
            boolean found = prov.stream().anyMatch(p ->
                    String.valueOf(p.get("content")).contains(e.inspectProvenanceContains));
            if (!found) {
                return "expected provenance to contain '" + e.inspectProvenanceContains + "'";
            }
        }
        return null;
    }

    private ObjectNode searchArgs(BenchScenario s, boolean includeInactive) {
        ObjectNode args = json.createObjectNode();
        args.put("query", s.query.text);
        if (s.query.budgetTokens != null) {
            args.put("budget_tokens", s.query.budgetTokens);
        }
        if (s.query.types != null) {
            ArrayNode types = args.putArray("types");
            s.query.types.forEach(types::add);
        }
        args.put("include_inactive", includeInactive || s.query.includeInactive);
        return args;
    }

    private ObjectNode objArg(String key, String value) {
        ObjectNode args = json.createObjectNode();
        args.put(key, value);
        return args;
    }

    private void resetDatabase() {
        jdbc.execute("TRUNCATE fact_relation, fact_provenance, fact, utterance, session CASCADE");
    }

    private double writeReport(Path reportPath, List<BenchScenario> scenarios,
                              List<Outcome> outcomes) throws IOException {
        Map<String, int[]> byCategory = new LinkedHashMap<>();
        for (String c : CATEGORY_ORDER) {
            byCategory.put(c, new int[]{0, 0});
        }
        int totalPass = 0;
        for (Outcome o : outcomes) {
            int[] counts = byCategory.computeIfAbsent(o.scenario().category, k -> new int[]{0, 0});
            counts[1]++;
            if (o.passed()) {
                counts[0]++;
                totalPass++;
            }
        }
        double overall = outcomes.isEmpty() ? 0 : 100.0 * totalPass / outcomes.size();

        StringBuilder sb = new StringBuilder();
        sb.append("# Benchmark report\n\n");
        sb.append("- Run at: ").append(OffsetDateTime.now()).append('\n');
        sb.append("- LLM provider: ").append(llmProperties.getProvider())
                .append(" (model ").append(llmProperties.getModel()).append(")\n");
        sb.append("- Embedding provider: ").append(embeddingProperties.getProvider())
                .append(" (model ").append(embeddingProvider.modelId())
                .append(", dim ").append(embeddingProvider.dimension()).append(")\n");
        sb.append("- Scenarios: ").append(scenarios.size()).append("\n\n");

        sb.append("## Category results\n\n");
        sb.append("| Category | Passed / Total | Rate |\n|---|---|---|\n");
        for (var entry : byCategory.entrySet()) {
            int[] c = entry.getValue();
            double rate = c[1] == 0 ? 0 : 100.0 * c[0] / c[1];
            sb.append("| ").append(entry.getKey()).append(" | ")
                    .append(c[0]).append(" / ").append(c[1]).append(" | ")
                    .append(String.format("%.0f%%", rate)).append(" |\n");
        }
        sb.append("| **overall** | **").append(totalPass).append(" / ")
                .append(outcomes.size()).append("** | **")
                .append(String.format("%.0f%%", overall)).append("** |\n\n");

        List<Outcome> failures = outcomes.stream().filter(o -> !o.passed()).toList();
        sb.append("## Failures (").append(failures.size()).append(")\n\n");
        if (failures.isEmpty()) {
            sb.append("None.\n");
        } else {
            for (Outcome o : failures) {
                sb.append("- `").append(o.scenario().id).append("` (")
                        .append(o.scenario().category).append("): ")
                        .append(o.reason()).append('\n');
            }
        }

        Files.writeString(reportPath, sb.toString());
        log.info("wrote {} (overall {}%)", reportPath, String.format("%.0f", overall));
        return overall;
    }
}
