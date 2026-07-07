package dev.nini.memstore.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nini.memstore.domain.FactType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Claude Messages API implementation of {@link LlmClient}. Determinism relies on
 * the model plus a fixed prompt: sampling parameters (temperature/top_p) are not
 * sent because Opus 4.7/4.8 reject them, and the {@code thinking} field is
 * omitted so the model answers directly.
 *
 * <p>Status: unverified in this environment - requires ANTHROPIC_API_KEY. The
 * request/response shapes follow the documented Messages API
 * (POST /v1/messages, x-api-key, anthropic-version 2023-06-01).
 */
public class ClaudeLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final String model;

    public ClaudeLlmClient(String baseUrl, String apiKey, String model) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
        this.model = model;
    }

    private String complete(String system, String user, int maxTokens) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", system,
                "messages", List.of(Map.of("role", "user", "content", user)));
        JsonNode response = client.post()
                .uri("/v1/messages")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.has("content")) {
            throw new IllegalStateException("empty LLM response");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.get("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString().trim();
    }

    @Override
    public List<ExtractionCandidate> extractFacts(List<UtteranceView> utterances) {
        StringBuilder log = new StringBuilder();
        for (UtteranceView u : utterances) {
            log.append(u.seq()).append(" [").append(u.role()).append("] ")
                    .append(u.content()).append('\n');
        }
        String system = """
                You extract memory-worthy facts from an agent session log for a \
                long-term memory store. Only keep durable facts an agent should \
                remember across sessions: user preferences, project constraints, \
                decisions, notable events, and key entities. Ignore chit-chat.
                Return ONLY a JSON array. Each element:
                {"statement": string, "type": "preference|constraint|decision|event|entity", \
                "explicit": boolean, "source_seqs": [int, ...]}
                explicit = true if stated directly, false if inferred. \
                source_seqs must reference the utterance sequence numbers the fact \
                came from. If nothing is memory-worthy, return [].""";
        String text = complete(system, log.toString(), 2000);
        return parseCandidates(text);
    }

    private List<ExtractionCandidate> parseCandidates(String text) {
        List<ExtractionCandidate> out = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(stripFences(text));
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode node : arr) {
                try {
                    String statement = node.path("statement").asText();
                    FactType type = FactType.fromCode(node.path("type").asText());
                    boolean explicit = node.path("explicit").asBoolean(true);
                    List<Integer> seqs = new ArrayList<>();
                    for (JsonNode s : node.path("source_seqs")) {
                        seqs.add(s.asInt());
                    }
                    if (!statement.isBlank() && !seqs.isEmpty()) {
                        out.add(new ExtractionCandidate(statement, type, explicit, seqs));
                    }
                } catch (RuntimeException e) {
                    log.warn("skipping malformed extraction candidate: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("failed to parse extraction response as JSON: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public boolean isSameFact(String candidate, String existing) {
        String system = "Answer only 'yes' or 'no'. Do two statements express the "
                + "same underlying fact?";
        String text = complete(system,
                "A: " + candidate + "\nB: " + existing, 10).toLowerCase();
        return text.startsWith("yes");
    }

    @Override
    public PairJudgment classifyPair(String candidate, String existing) {
        String system = """
                Classify the relationship of a NEW fact to an EXISTING active fact. \
                Answer with exactly one word: duplicate, contradicts, refines, or \
                unrelated.""";
        String text = complete(system,
                "NEW: " + candidate + "\nEXISTING: " + existing, 10)
                .toLowerCase().trim();
        if (text.startsWith("duplicate")) {
            return PairJudgment.DUPLICATE;
        }
        if (text.startsWith("contradicts")) {
            return PairJudgment.CONTRADICTS;
        }
        if (text.startsWith("refines")) {
            return PairJudgment.REFINES;
        }
        return PairJudgment.UNRELATED;
    }

    @Override
    public boolean isCorrection(String statement) {
        String system = "Answer only 'yes' or 'no'. Is this statement an explicit "
                + "correction or retraction of a previous statement?";
        return complete(system, statement, 10).toLowerCase().startsWith("yes");
    }

    @Override
    public ReviewJudgment reviewFact(String statement, List<String> activeContext) {
        String system = """
                A stored fact was flagged for review after a related fact changed. \
                Given the current active facts, decide whether to KEEP it, mark it \
                SUPERSEDE (an active fact replaces it), or RETRACT it (no longer \
                true). Answer with exactly one word: keep, supersede, or retract.""";
        String user = "FLAGGED: " + statement + "\nACTIVE:\n"
                + String.join("\n", activeContext);
        String text = complete(system, user, 10).toLowerCase().trim();
        if (text.startsWith("supersede")) {
            return ReviewJudgment.SUPERSEDE;
        }
        if (text.startsWith("retract")) {
            return ReviewJudgment.RETRACT;
        }
        return ReviewJudgment.KEEP;
    }

    private static String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) {
                t = t.substring(firstNl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }
}
