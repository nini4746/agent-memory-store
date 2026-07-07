package dev.nini.memstore.llm;

import dev.nini.memstore.domain.FactType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, rule-based LLM stand-in. It lets the entire pipeline
 * (extraction, dedupe, conflict classification, review) run without any API key
 * and gives tests stable, predictable behaviour. The real model path is
 * {@link ClaudeLlmClient}.
 *
 * <p>The rules are intentionally simple keyword/heuristic matching over English
 * and Korean tokens; test and demo utterances are written to exercise them.
 */
public class FakeLlmClient implements LlmClient {

    private static final Pattern TOKEN = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private static final List<String> PREFERENCE = List.of(
            "prefer", "prefers", "like", "likes", "favor", "rather",
            "선호", "좋아", "선호합니다");
    private static final List<String> CONSTRAINT = List.of(
            "must", "should", "require", "required", "constraint", "cannot",
            "never", "always", "제약", "해야", "필수", "금지");
    private static final List<String> DECISION = List.of(
            "decide", "decided", "use", "using", "migrate", "migrated",
            "choose", "chose", "adopt", "deploy", "deployed",
            "결정", "사용", "채택", "마이그레이션", "배포", "이전");
    private static final List<String> EVENT = List.of(
            "happened", "released", "launched", "occurred", "shipped", "met",
            "출시", "발생", "완료");
    private static final List<String> CHITCHAT = List.of(
            "hello", "hi", "hey", "thanks", "thank", "weather", "lol", "bye",
            "안녕", "감사", "고마", "날씨", "ㅋㅋ");
    private static final List<String> CORRECTION = List.of(
            "actually no", "no longer", "correction", "i was wrong", "scratch that",
            "정정", "취소", "잘못", "아니라 이제", "정정합니다");
    private static final List<String> INFERRED = List.of(
            "probably", "maybe", "i think", "seems", "아마", "추측", "듯");

    @Override
    public List<ExtractionCandidate> extractFacts(List<UtteranceView> utterances) {
        List<ExtractionCandidate> candidates = new ArrayList<>();
        for (UtteranceView u : utterances) {
            if (!"user".equalsIgnoreCase(u.role())) {
                continue;
            }
            String lower = u.content().toLowerCase();
            if (containsAny(lower, CHITCHAT) && !hasFactSignal(lower)) {
                continue;
            }
            FactType type = classifyType(lower);
            if (type == null) {
                continue;
            }
            boolean explicit = !containsAny(lower, INFERRED);
            candidates.add(new ExtractionCandidate(
                    u.content().trim(), type, explicit, List.of(u.seq())));
        }
        return candidates;
    }

    private boolean hasFactSignal(String lower) {
        return classifyType(lower) != null;
    }

    private FactType classifyType(String lower) {
        if (containsAny(lower, PREFERENCE)) {
            return FactType.PREFERENCE;
        }
        if (containsAny(lower, CONSTRAINT)) {
            return FactType.CONSTRAINT;
        }
        if (containsAny(lower, DECISION)) {
            return FactType.DECISION;
        }
        if (containsAny(lower, EVENT)) {
            return FactType.EVENT;
        }
        return null;
    }

    @Override
    public boolean isSameFact(String candidate, String existing) {
        return jaccard(tokens(candidate), tokens(existing)) >= 0.85;
    }

    @Override
    public PairJudgment classifyPair(String candidate, String existing) {
        Set<String> a = tokens(candidate);
        Set<String> b = tokens(existing);
        double overlap = jaccard(a, b);
        if (overlap >= 0.85) {
            return PairJudgment.DUPLICATE;
        }
        boolean sharedSubject = hasSharedNonNumericToken(a, b);
        if (sharedSubject && (numbersDiffer(a, b) || negationDiffers(candidate, existing))) {
            return PairJudgment.CONTRADICTS;
        }
        if (overlap >= 0.4) {
            return PairJudgment.REFINES;
        }
        return PairJudgment.UNRELATED;
    }

    @Override
    public boolean isCorrection(String statement) {
        return containsAny(statement.toLowerCase(), CORRECTION);
    }

    @Override
    public ReviewJudgment reviewFact(String statement, List<String> activeContext) {
        // The fake keeps facts by default (clears the review flag). A directly
        // contradicting active statement triggers a retract.
        for (String ctx : activeContext) {
            if (classifyPair(statement, ctx) == PairJudgment.CONTRADICTS) {
                return ReviewJudgment.RETRACT;
            }
        }
        return ReviewJudgment.KEEP;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> tokens(String text) {
        Set<String> set = new HashSet<>(Arrays.asList(TOKEN.split(text.toLowerCase())));
        set.remove("");
        return set;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    private static boolean hasSharedNonNumericToken(Set<String> a, Set<String> b) {
        for (String t : a) {
            if (!t.isEmpty() && !t.chars().allMatch(Character::isDigit) && b.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean numbersDiffer(Set<String> a, Set<String> b) {
        Set<String> na = numeric(a);
        Set<String> nb = numeric(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        return !na.equals(nb);
    }

    private static Set<String> numeric(Set<String> tokens) {
        Set<String> out = new HashSet<>();
        for (String t : tokens) {
            if (!t.isEmpty() && t.chars().allMatch(Character::isDigit)) {
                out.add(t);
            }
        }
        return out;
    }

    private static final List<String> NEGATIONS = List.of("not", "no", "안", "못", "없");

    private static boolean negationDiffers(String a, String b) {
        return hasNegation(a.toLowerCase()) != hasNegation(b.toLowerCase());
    }

    private static boolean hasNegation(String s) {
        return containsAny(s, NEGATIONS);
    }
}
