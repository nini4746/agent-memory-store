package dev.nini.memstore.llm;

import dev.nini.memstore.domain.FactType;
import java.util.List;

/**
 * Abstraction over the LLM used for extraction, duplicate/conflict
 * classification, and review. Implementations must be deterministic (the real
 * Claude client runs the model without sampling parameters; the fake is
 * rule-based) so benchmark and test results are stable.
 */
public interface LlmClient {

    /** A single utterance passed to the model, keyed by its sequence number. */
    record UtteranceView(int seq, String role, String content) {
    }

    /** A candidate fact the model proposes from a session. */
    record ExtractionCandidate(String statement, FactType type, boolean explicit,
                               List<Integer> sourceSeqs) {
    }

    /** Result of the pairwise conflict classification (SPEC R4). */
    enum PairJudgment {
        DUPLICATE,
        CONTRADICTS,
        REFINES,
        UNRELATED
    }

    /** Result of a needs_review re-evaluation (SPEC R6). */
    enum ReviewJudgment {
        KEEP,
        SUPERSEDE,
        RETRACT
    }

    /**
     * Extracts memory-worthy facts from a session's utterances. Returns an empty
     * list when nothing is worth remembering (SPEC R2-A3).
     */
    List<ExtractionCandidate> extractFacts(List<UtteranceView> utterances);

    /** Confirms whether two statements express the same fact (dedupe, SPEC R2). */
    boolean isSameFact(String candidate, String existing);

    /** Classifies a new candidate against an existing active fact (SPEC R4). */
    PairJudgment classifyPair(String candidate, String existing);

    /**
     * Detects whether a statement is an explicit correction speech act
     * (SPEC R4 explicit-correction path).
     */
    boolean isCorrection(String statement);

    /**
     * Re-evaluates a needs_review fact against the current active fact set
     * (SPEC R6). Returns KEEP to clear the flag, or SUPERSEDE/RETRACT.
     */
    ReviewJudgment reviewFact(String statement, List<String> activeContext);
}
