package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.RelationType;
import dev.nini.memstore.mcp.McpServer;
import dev.nini.memstore.service.FactStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Plan 09 verification of the MCP tool layer (SPEC R7-A1..A4). */
@SpringBootTest
class McpIntegrationTest extends AbstractPostgresIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    McpServer mcpServer;
    @Autowired
    FactStore factStore;

    private JsonNode rpc(String method, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        return mcpServer.handle(req);
    }

    private JsonNode callTool(String name, ObjectNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        JsonNode response = rpc("tools/call", params);
        String text = response.path("result").path("content").get(0).path("text").asText();
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void r7a1_toolsListExposesSevenTools() {
        JsonNode response = rpc("tools/list", null);
        JsonNode tools = response.path("result").path("tools");
        assertThat(tools).hasSize(7);
        for (JsonNode tool : tools) {
            assertThat(tool.hasNonNull("name")).isTrue();
            assertThat(tool.hasNonNull("description")).isTrue();
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
        }
        assertThat(response.path("result").path("tools").findValuesAsText("name"))
                .contains("memory_log_append", "memory_extract", "memory_search",
                        "memory_save_fact", "memory_correct", "memory_forget",
                        "memory_inspect");
    }

    @Test
    void r7a2_searchRoundTripSucceeds() {
        ObjectNode append = mapper.createObjectNode();
        append.put("source", "chat");
        var entries = append.putArray("entries");
        var e = entries.addObject();
        e.put("role", "user");
        e.put("content", "We decided to use PostgreSQL for the database");
        JsonNode appended = callTool("memory_log_append", append);
        String sessionId = appended.path("session_id").asText();

        ObjectNode extractArgs = mapper.createObjectNode();
        extractArgs.put("session_id", sessionId);
        callTool("memory_extract", extractArgs);

        ObjectNode searchArgs = mapper.createObjectNode();
        searchArgs.put("query", "database choice");
        searchArgs.put("budget_tokens", 2000);
        JsonNode result = callTool("memory_search", searchArgs);

        assertThat(result.path("hits").isArray()).isTrue();
        assertThat(result.path("hits")).isNotEmpty();
        assertThat(result.path("hits").get(0).hasNonNull("fact_id")).isTrue();
        assertThat(result.path("hits").get(0).path("provenance_utterance_ids").isArray())
                .isTrue();
    }

    @Test
    void r7a3_correctTriggersConflictAndPropagation() {
        UUID a = saveFact("The primary datastore is Redis", "decision");
        UUID b = saveFact("Sessions expire after sixty minutes", "constraint");
        factStore.addRelation(b, a, RelationType.REFINES);

        ObjectNode correctArgs = mapper.createObjectNode();
        correctArgs.put("fact_id", a.toString());
        correctArgs.put("correction_statement", "The primary datastore is Memcached");
        JsonNode result = callTool("memory_correct", correctArgs);
        assertThat(result.hasNonNull("correction_fact_id")).isTrue();

        assertThat(factStore.find(a).orElseThrow().getStatus())
                .isEqualTo(FactStatus.RETRACTED);
        assertThat(factStore.find(b).orElseThrow().isNeedsReview()).isTrue();
    }

    @Test
    void r7a4_saveFactHasSyntheticProvenance() {
        UUID factId = saveFact("The team prefers trunk-based development", "preference");
        FactEntity fact = factStore.find(factId).orElseThrow();
        assertThat(fact.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(factStore.provenanceUtterances(factId)).isNotEmpty();
    }

    private UUID saveFact(String statement, String type) {
        ObjectNode args = mapper.createObjectNode();
        args.put("statement", statement);
        args.put("type", type);
        return UUID.fromString(callTool("memory_save_fact", args).path("fact_id").asText());
    }
}
