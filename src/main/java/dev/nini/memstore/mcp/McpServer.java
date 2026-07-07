package dev.nini.memstore.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal MCP JSON-RPC 2.0 handler (SPEC R7). Exposes the seven memory tools via
 * {@code tools/list} and {@code tools/call}. Transport-agnostic: it maps a
 * request node to a response node; the stdio runner drives it for real clients.
 */
@Component
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpToolService tools;
    private final Map<String, Function<JsonNode, Map<String, Object>>> dispatch =
            new LinkedHashMap<>();

    public McpServer(McpToolService tools) {
        this.tools = tools;
        dispatch.put("memory_log_append", tools::logAppend);
        dispatch.put("memory_extract", tools::extract);
        dispatch.put("memory_search", tools::search);
        dispatch.put("memory_save_fact", tools::saveFact);
        dispatch.put("memory_correct", tools::correct);
        dispatch.put("memory_forget", tools::forget);
        dispatch.put("memory_inspect", tools::inspect);
    }

    /** Handles one request; returns null for notifications (no reply expected). */
    public JsonNode handle(JsonNode request) {
        String method = request.path("method").asText();
        JsonNode id = request.get("id");
        try {
            switch (method) {
                case "initialize" -> {
                    return success(id, initializeResult());
                }
                case "notifications/initialized" -> {
                    return null;
                }
                case "ping" -> {
                    return success(id, mapper.createObjectNode());
                }
                case "tools/list" -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.set("tools", toolList());
                    return success(id, result);
                }
                case "tools/call" -> {
                    return handleToolCall(id, request.path("params"));
                }
                default -> {
                    return error(id, -32601, "method not found: " + method);
                }
            }
        } catch (RuntimeException e) {
            log.warn("MCP request failed: {}", e.getMessage());
            return error(id, -32603, "internal error: " + e.getMessage());
        }
    }

    private JsonNode handleToolCall(JsonNode id, JsonNode params) {
        String name = params.path("name").asText();
        Function<JsonNode, Map<String, Object>> fn = dispatch.get(name);
        if (fn == null) {
            return error(id, -32602, "unknown tool: " + name);
        }
        JsonNode arguments = params.has("arguments")
                ? params.get("arguments") : mapper.createObjectNode();
        try {
            Map<String, Object> result = fn.apply(arguments);
            return success(id, toolContent(result, false));
        } catch (IllegalArgumentException e) {
            // Tool-level error surfaces as an isError result, not a protocol error.
            return success(id, toolContent(Map.of("error", e.getMessage()), true));
        }
    }

    private ObjectNode toolContent(Map<String, Object> payload, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", writeJson(payload));
        result.put("isError", isError);
        return result;
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "agent-memory-store");
        info.put("version", "0.1.0");
        return result;
    }

    private ArrayNode toolList() {
        ArrayNode tools = mapper.createArrayNode();

        tools.add(tool("memory_log_append",
                "Append raw utterances to a session log (lossless). Creates a "
                        + "session when session_id is omitted. Use before extraction.",
                schema(props -> {
                    props.set("session_id", strProp("Existing session id (optional)"));
                    props.set("source", strProp("Session source label (optional)"));
                    ObjectNode entries = props.putObject("entries");
                    entries.put("type", "array");
                    entries.put("description", "Ordered utterances to append");
                    ObjectNode items = entries.putObject("items");
                    items.put("type", "object");
                    ObjectNode ep = items.putObject("properties");
                    ep.set("role", strProp("user or assistant"));
                    ep.set("content", strProp("Raw utterance text"));
                    items.putArray("required").add("role").add("content");
                }, "entries")));

        tools.add(tool("memory_extract",
                "Run the extraction pipeline over a session: extract memory-worthy "
                        + "facts, dedupe, and resolve conflicts. Returns counts.",
                schema(props -> props.set("session_id",
                        strProp("Session to extract from")), "session_id")));

        tools.add(tool("memory_search",
                "Retrieve relevant memories ranked by similarity, recency, and "
                        + "confidence, packed within a token budget. Use to recall "
                        + "facts; use memory_inspect for full detail on one fact.",
                schema(props -> {
                    props.set("query", strProp("Natural-language query"));
                    props.set("budget_tokens",
                            intProp("Token budget (default 2000, max 8000)"));
                    ObjectNode types = props.putObject("types");
                    types.put("type", "array");
                    types.put("description",
                            "Filter by fact types: preference|constraint|decision|event|entity");
                    types.putObject("items").put("type", "string");
                    props.set("include_inactive",
                            boolProp("Include superseded/retracted/expired facts"));
                }, "query")));

        tools.add(tool("memory_save_fact",
                "Directly store a single fact. Creates synthetic provenance so the "
                        + "fact is auditable; runs conflict resolution.",
                schema(props -> {
                    props.set("statement", strProp("The fact to remember"));
                    props.set("type", strProp(
                            "preference|constraint|decision|event|entity"));
                }, "statement", "type")));

        tools.add(tool("memory_correct",
                "Explicitly correct a fact: retracts the target and stores the "
                        + "correction, then propagates review flags to related facts.",
                schema(props -> {
                    props.set("fact_id", strProp("Fact to correct"));
                    props.set("correction_statement", strProp("The corrected fact"));
                }, "fact_id", "correction_statement")));

        tools.add(tool("memory_forget",
                "Retract a fact with no replacement (soft delete). The reason is "
                        + "recorded in the append-only log.",
                schema(props -> {
                    props.set("fact_id", strProp("Fact to forget"));
                    props.set("reason", strProp("Why it is being forgotten"));
                }, "fact_id")));

        tools.add(tool("memory_inspect",
                "Full detail on one fact: status, confidence, provenance (source "
                        + "sessions and original utterances), relations, and supersede "
                        + "history.",
                schema(props -> props.set("fact_id",
                        strProp("Fact to inspect")), "fact_id")));

        return tools;
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        t.set("inputSchema", inputSchema);
        return t;
    }

    private ObjectNode schema(java.util.function.Consumer<ObjectNode> propsBuilder,
                              String... required) {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        propsBuilder.accept(props);
        ArrayNode req = s.putArray("required");
        for (String r : required) {
            req.add(r);
        }
        return s;
    }

    private ObjectNode strProp(String description) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private ObjectNode intProp(String description) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", "integer");
        p.put("description", description);
        return p;
    }

    private ObjectNode boolProp(String description) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", "boolean");
        p.put("description", description);
        return p;
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? mapper.nullNode() : id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? mapper.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
