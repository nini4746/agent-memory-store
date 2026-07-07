package dev.nini.memstore.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nini.memstore.MemStoreApplication;
import dev.nini.memstore.mcp.McpToolService;
import dev.nini.memstore.service.SessionLogService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Three-session demo (SPEC R9). Seeds a March/May/June history, runs extraction
 * and an explicit correction, then answers four queries that show conflict
 * resolution, provenance/supersede history, budget packing, and a reflected
 * correction. Uses the configured providers (fakes by default, deterministic).
 */
public final class DemoRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DemoRunner() {
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MemStoreApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx = app.run(args)) {
            McpToolService tools = ctx.getBean(McpToolService.class);
            SessionLogService sessionLog = ctx.getBean(SessionLogService.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            jdbc.execute("TRUNCATE fact_relation, fact_provenance, fact, utterance, "
                    + "session CASCADE");

            OffsetDateTime now = OffsetDateTime.now();
            System.out.println("=== agent-memory-store demo ===\n");

            System.out.println("[SETUP] Seeding three sessions across time");
            var march = sessionLog.append(null, "march", List.of(
                    entry("We decided to use PostgreSQL version 15"),
                    entry("We decided to deploy on AWS")), now.minusMonths(4));
            tools.extract(arg("session_id", march.toString()));
            System.out.println("  March: PostgreSQL 15 + AWS");

            var may = sessionLog.append(null, "may", List.of(
                    entry("The team prefers spaces over tabs for indentation")),
                    now.minusMonths(2));
            tools.extract(arg("session_id", may.toString()));
            System.out.println("  May:   prefers spaces over tabs");

            var june = sessionLog.append(null, "june", List.of(
                    entry("We decided to migrate to PostgreSQL version 17")),
                    now.minusMonths(1));
            tools.extract(arg("session_id", june.toString()));
            System.out.println("  June:  migrate to PostgreSQL 17 (supersedes 15)");

            // Explicit correction of the May preference.
            String prefId = jdbc.queryForList(
                    "SELECT id::text FROM fact WHERE status='active' AND statement LIKE '%spaces over tabs%'",
                    String.class).get(0);
            ObjectNode correctArgs = JSON.createObjectNode();
            correctArgs.put("fact_id", prefId);
            correctArgs.put("correction_statement",
                    "The team prefers tabs over spaces for indentation");
            tools.correct(correctArgs);
            System.out.println("  June:  correction - now prefers tabs over spaces\n");

            // Q1: conflict resolution.
            System.out.println("[Q1 | conflict resolution] query: 'PostgreSQL version'");
            printHits(tools.search(searchArg("PostgreSQL version", 2000, false)));

            // Q2: provenance and supersede history of the retired PostgreSQL 15 fact.
            System.out.println("\n[Q2 | provenance + supersede history] inspect the "
                    + "superseded PostgreSQL 15 fact");
            String supersededId = jdbc.queryForList(
                    "SELECT id::text FROM fact WHERE statement LIKE '%PostgreSQL version 15%'",
                    String.class).get(0);
            printInspect(tools.inspect(arg("fact_id", supersededId)));

            // Q3: budget packing under a tight budget.
            System.out.println("\n[Q3 | budget packing] query: 'project decisions' "
                    + "budget_tokens=20");
            printHits(tools.search(searchArg("project decisions", 20, false)));

            // Q4: corrected preference reflected.
            System.out.println("\n[Q4 | correction reflected] query: 'indentation preference'");
            printHits(tools.search(searchArg("indentation preference", 2000, false)));

            System.out.println("\n=== demo complete ===");
        }
    }

    @SuppressWarnings("unchecked")
    private static void printHits(java.util.Map<String, Object> result) {
        List<java.util.Map<String, Object>> hits =
                (List<java.util.Map<String, Object>>) result.get("hits");
        System.out.println("  estimated_tokens=" + result.get("estimated_tokens")
                + (result.get("warning") != null ? " warning=" + result.get("warning") : ""));
        if (hits.isEmpty()) {
            System.out.println("  (no facts)");
        }
        for (var hit : hits) {
            System.out.printf("  - [%s] %s (conf=%.2f, score=%.3f)%n",
                    hit.get("type"), hit.get("statement"), toDouble(hit.get("confidence")),
                    toDouble(hit.get("score")));
        }
    }

    @SuppressWarnings("unchecked")
    private static void printInspect(java.util.Map<String, Object> f) {
        System.out.println("  statement: " + f.get("statement"));
        System.out.println("  status: " + f.get("status")
                + "  superseded_by: " + f.get("superseded_by"));
        List<java.util.Map<String, Object>> prov =
                (List<java.util.Map<String, Object>>) f.get("provenance");
        for (var p : prov) {
            System.out.println("  provenance: session=" + p.get("session_id")
                    + " seq=" + p.get("seq") + " \"" + p.get("content") + "\"");
        }
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static SessionLogService.LogEntry entry(String content) {
        return new SessionLogService.LogEntry("user", content);
    }

    private static ObjectNode arg(String key, String value) {
        ObjectNode node = JSON.createObjectNode();
        node.put(key, value);
        return node;
    }

    private static ObjectNode searchArg(String query, int budget, boolean includeInactive) {
        ObjectNode node = JSON.createObjectNode();
        node.put("query", query);
        node.put("budget_tokens", budget);
        node.put("include_inactive", includeInactive);
        return node;
    }
}
