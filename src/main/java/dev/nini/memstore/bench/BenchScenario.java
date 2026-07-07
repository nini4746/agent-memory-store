package dev.nini.memstore.bench;

import java.util.List;

/**
 * A MemFail-style benchmark scenario (SPEC R8), loaded from YAML. All fields
 * except id/category are optional so scenarios stay concise.
 */
public class BenchScenario {

    public String id;
    public String category;
    public String description;
    public List<Session> sessions;
    public boolean extract;
    public List<AgeOp> age;
    public List<CorrectOp> correct;
    public boolean maintenance;
    public Query query;
    public Expect expect;

    public static class Session {
        public String source;
        public int offsetDays;
        public List<Utterance> utterances;
    }

    public static class Utterance {
        public String role = "user";
        public String content;
    }

    public static class AgeOp {
        public String contains;
        public int days;
    }

    public static class CorrectOp {
        public String targetContains;
        public String statement;
    }

    public static class Query {
        public String text;
        public Integer budgetTokens;
        public List<String> types;
        public boolean includeInactive;
    }

    public static class Expect {
        public List<String> containsAll;
        public List<String> excludes;
        public Integer activeHitCount;
        public Integer activeHitCountLt;
        public Boolean tokensWithinBudget;
        public String inactiveContains;
        public String inspectProvenanceContains;
    }
}
