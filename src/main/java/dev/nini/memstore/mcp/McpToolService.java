package dev.nini.memstore.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactRelationEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.domain.UtteranceEntity;
import dev.nini.memstore.embedding.EmbeddingProvider;
import dev.nini.memstore.extraction.ConflictResolver;
import dev.nini.memstore.extraction.CorrectionService;
import dev.nini.memstore.extraction.ExtractionService;
import dev.nini.memstore.extraction.FactCandidate;
import dev.nini.memstore.extraction.PropagationService;
import dev.nini.memstore.search.MemorySearchService;
import dev.nini.memstore.service.FactStore;
import dev.nini.memstore.service.SessionLogService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Business logic behind the seven MCP tools (SPEC R7). Each method takes parsed
 * arguments and returns a plain map/list structure that the JSON-RPC layer
 * serializes. The provenance invariant (N4) holds everywhere: direct saves and
 * corrections create synthetic sessions/utterances.
 */
@Service
public class McpToolService {

    private final SessionLogService sessionLog;
    private final ExtractionService extraction;
    private final MemorySearchService search;
    private final FactStore factStore;
    private final CorrectionService correctionService;
    private final ConflictResolver conflictResolver;
    private final PropagationService propagation;
    private final EmbeddingProvider embeddingProvider;

    public McpToolService(SessionLogService sessionLog, ExtractionService extraction,
                          MemorySearchService search, FactStore factStore,
                          CorrectionService correctionService,
                          ConflictResolver conflictResolver,
                          PropagationService propagation,
                          EmbeddingProvider embeddingProvider) {
        this.sessionLog = sessionLog;
        this.extraction = extraction;
        this.search = search;
        this.factStore = factStore;
        this.correctionService = correctionService;
        this.conflictResolver = conflictResolver;
        this.propagation = propagation;
        this.embeddingProvider = embeddingProvider;
    }

    public Map<String, Object> logAppend(JsonNode args) {
        UUID sessionId = optionalUuid(args, "session_id");
        String source = args.hasNonNull("source") ? args.get("source").asText() : null;
        List<SessionLogService.LogEntry> entries = new ArrayList<>();
        for (JsonNode e : args.path("entries")) {
            entries.add(new SessionLogService.LogEntry(
                    e.path("role").asText("user"), e.path("content").asText("")));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        UUID resolved = sessionLog.append(sessionId, source, entries);
        return Map.of("session_id", resolved.toString(), "appended", entries.size());
    }

    public Map<String, Object> extract(JsonNode args) {
        UUID sessionId = requireUuid(args, "session_id");
        ExtractionService.ExtractionResult result = extraction.extract(sessionId);
        return Map.of("created", result.created(), "confirmed", result.confirmed(),
                "skipped", result.skipped());
    }

    public Map<String, Object> search(JsonNode args) {
        String query = args.path("query").asText("");
        Integer budget = args.hasNonNull("budget_tokens")
                ? args.get("budget_tokens").asInt() : null;
        List<FactType> types = null;
        if (args.hasNonNull("types") && args.get("types").isArray()) {
            types = new ArrayList<>();
            for (JsonNode t : args.get("types")) {
                types.add(FactType.fromCode(t.asText()));
            }
        }
        boolean includeInactive = args.path("include_inactive").asBoolean(false);

        MemorySearchService.SearchResponse response =
                search.search(query, budget, types, includeInactive);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (MemorySearchService.MemoryHit hit : response.hits()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("fact_id", hit.factId().toString());
            h.put("statement", hit.statement());
            h.put("type", hit.type().code());
            h.put("confidence", hit.confidence());
            h.put("needs_review", hit.needsReview());
            h.put("score", hit.score());
            h.put("provenance_utterance_ids", hit.provenanceUtteranceIds()
                    .stream().map(UUID::toString).toList());
            hits.add(h);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", hits);
        result.put("estimated_tokens", response.estimatedTokens());
        result.put("warning", response.warning());
        return result;
    }

    public Map<String, Object> saveFact(JsonNode args) {
        String statement = args.path("statement").asText("");
        if (statement.isBlank()) {
            throw new IllegalArgumentException("statement must not be blank");
        }
        FactType type = FactType.fromCode(args.path("type").asText());

        // Synthetic session/utterance guarantees provenance (N4, R7-A4).
        UUID sessionId = sessionLog.append(null, "direct",
                List.of(new SessionLogService.LogEntry("user", statement)));
        UtteranceEntity utterance = sessionLog.utterancesForSession(sessionId).get(0);
        float[] embedding = embeddingProvider.embed(statement);
        FactCandidate candidate = new FactCandidate(statement, type, 0.9, embedding,
                embeddingProvider.modelId(), List.of(utterance.getId()), sessionId,
                utterance.getSeq(), false);
        FactEntity fact = conflictResolver.handleNewFact(candidate);
        return Map.of("fact_id", fact.getId().toString(), "status",
                fact.getStatus().code());
    }

    public Map<String, Object> correct(JsonNode args) {
        UUID factId = requireUuid(args, "fact_id");
        String correction = args.path("correction_statement").asText("");
        FactEntity result = correctionService.correct(factId, correction);
        return Map.of("correction_fact_id", result.getId().toString(),
                "retracted_fact_id", factId.toString());
    }

    public Map<String, Object> forget(JsonNode args) {
        UUID factId = requireUuid(args, "fact_id");
        String reason = args.path("reason").asText("");
        factStore.find(factId).orElseThrow(
                () -> new IllegalArgumentException("no such fact: " + factId));
        // Record the reason as an immutable audit utterance and retract the fact.
        sessionLog.append(null, "forget",
                List.of(new SessionLogService.LogEntry("system",
                        "forget " + factId + ": " + reason)));
        factStore.transition(factId, FactStatus.RETRACTED, null);
        propagation.propagateFrom(factId);
        return Map.of("fact_id", factId.toString(), "status", "retracted",
                "reason", reason);
    }

    public Map<String, Object> inspect(JsonNode args) {
        UUID factId = requireUuid(args, "fact_id");
        FactEntity fact = factStore.find(factId).orElseThrow(
                () -> new IllegalArgumentException("no such fact: " + factId));

        List<Map<String, Object>> provenance = new ArrayList<>();
        for (UtteranceEntity u : factStore.provenanceUtterances(factId)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("utterance_id", u.getId().toString());
            p.put("session_id", u.getSessionId().toString());
            p.put("seq", u.getSeq());
            p.put("role", u.getRole());
            p.put("content", u.getContent());
            provenance.add(p);
        }

        List<Map<String, Object>> relations = new ArrayList<>();
        for (FactRelationEntity r : factStore.relationsFrom(factId)) {
            relations.add(relationMap(r, "out"));
        }
        for (FactRelationEntity r : factStore.relationsTo(factId)) {
            relations.add(relationMap(r, "in"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fact_id", fact.getId().toString());
        result.put("statement", fact.getStatement());
        result.put("type", fact.getType().code());
        result.put("status", fact.getStatus().code());
        result.put("confidence", fact.getConfidence());
        result.put("needs_review", fact.isNeedsReview());
        result.put("created_at", String.valueOf(fact.getCreatedAt()));
        result.put("last_confirmed_at", String.valueOf(fact.getLastConfirmedAt()));
        result.put("invalidated_at", String.valueOf(fact.getInvalidatedAt()));
        result.put("superseded_by", fact.getSupersededBy() == null ? null
                : fact.getSupersededBy().toString());
        result.put("provenance", provenance);
        result.put("relations", relations);
        return result;
    }

    private Map<String, Object> relationMap(FactRelationEntity r, String direction) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("direction", direction);
        m.put("from_fact_id", r.getFromFactId().toString());
        m.put("to_fact_id", r.getToFactId().toString());
        m.put("relation", r.getRelation().code());
        return m;
    }

    private static UUID requireUuid(JsonNode args, String field) {
        if (!args.hasNonNull(field)) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        return UUID.fromString(args.get(field).asText());
    }

    private static UUID optionalUuid(JsonNode args, String field) {
        return args.hasNonNull(field) ? UUID.fromString(args.get(field).asText()) : null;
    }
}
