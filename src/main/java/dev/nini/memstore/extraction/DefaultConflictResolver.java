package dev.nini.memstore.extraction;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.RelationType;
import dev.nini.memstore.domain.SessionEntity;
import dev.nini.memstore.llm.LlmClient;
import dev.nini.memstore.repo.SessionRepository;
import dev.nini.memstore.search.FactVectorSearch;
import dev.nini.memstore.search.ScoredFactRow;
import dev.nini.memstore.service.FactStore;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Conflict detection and resolution (SPEC R4). Detects candidates similar to
 * existing active facts (embedding similarity >= 0.80), classifies each pair
 * with the LLM, and applies the priority rules: explicit correction &gt; newest
 * session &gt; newest utterance.
 */
@Primary
@Component
public class DefaultConflictResolver implements ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultConflictResolver.class);
    private static final double CANDIDATE_THRESHOLD = 0.80;
    private static final double REVIVAL_THRESHOLD = 0.92;

    private record ProvenanceContext(OffsetDateTime sessionStartedAt, int seq) {
    }

    private final FactStore factStore;
    private final FactVectorSearch vectorSearch;
    private final LlmClient llm;
    private final SessionRepository sessions;
    private final PropagationService propagation;
    private final JdbcTemplate jdbc;

    public DefaultConflictResolver(FactStore factStore, FactVectorSearch vectorSearch,
                                   LlmClient llm, SessionRepository sessions,
                                   PropagationService propagation, JdbcTemplate jdbc) {
        this.factStore = factStore;
        this.vectorSearch = vectorSearch;
        this.llm = llm;
        this.sessions = sessions;
        this.propagation = propagation;
        this.jdbc = jdbc;
    }

    @Override
    public FactEntity handleNewFact(FactCandidate candidate) {
        // A statement matching a retracted fact must not auto-revive it (R4-A4).
        if (matchesRetracted(candidate)) {
            log.info("candidate matches a retracted fact; saving as needs_review");
            return saveNew(candidate, true);
        }

        List<ScoredFactRow> nearby;
        try {
            nearby = vectorSearch.topK(candidate.embedding(), 5, List.of(FactStatus.ACTIVE))
                    .stream().filter(r -> r.similarity() >= CANDIDATE_THRESHOLD).toList();
        } catch (RuntimeException e) {
            log.warn("vector search failed during conflict detection: {}", e.getMessage());
            return saveNew(candidate, false);
        }
        if (nearby.isEmpty()) {
            return saveNew(candidate, false);
        }

        ScoredFactRow contradiction = null;
        ScoredFactRow refinement = null;
        for (ScoredFactRow existing : nearby) {
            LlmClient.PairJudgment judgment;
            try {
                judgment = llm.classifyPair(candidate.statement(), existing.statement());
            } catch (RuntimeException e) {
                log.warn("pair classification failed; saving candidate as needs_review");
                return saveNew(candidate, true);
            }
            switch (judgment) {
                case DUPLICATE -> {
                    return factStore.confirm(existing.id());
                }
                case CONTRADICTS -> {
                    if (contradiction == null) {
                        contradiction = existing;
                    }
                }
                case REFINES -> {
                    if (refinement == null) {
                        refinement = existing;
                    }
                }
                case UNRELATED -> {
                    // ignore
                }
            }
        }

        if (contradiction != null) {
            return resolveContradiction(candidate, contradiction);
        }
        if (refinement != null) {
            FactEntity saved = saveNew(candidate, false);
            factStore.addRelation(saved.getId(), refinement.id(), RelationType.REFINES);
            return saved;
        }
        return saveNew(candidate, false);
    }

    private FactEntity resolveContradiction(FactCandidate candidate, ScoredFactRow existing) {
        ProvenanceContext existingCtx = provenanceContext(existing.id());
        OffsetDateTime candidateStarted = candidateSessionStart(candidate);

        int cmp = compareRecency(candidateStarted, candidate.maxSeq(),
                existingCtx.sessionStartedAt(), existingCtx.seq());

        if (cmp == 0) {
            // Same utterance: keep both, both need review (R4-A3).
            FactEntity saved = saveNew(candidate, true);
            factStore.setNeedsReview(existing.id(), true);
            factStore.addRelation(saved.getId(), existing.id(), RelationType.CONTRADICTS);
            return saved;
        }

        FactEntity saved = saveNew(candidate, false);
        if (cmp > 0) {
            // Candidate wins; existing is superseded.
            factStore.transition(existing.id(), FactStatus.SUPERSEDED, saved.getId());
            factStore.addRelation(saved.getId(), existing.id(), RelationType.CONTRADICTS);
            propagation.propagateFrom(existing.id());
        } else {
            // Existing wins; candidate is stored then superseded.
            factStore.transition(saved.getId(), FactStatus.SUPERSEDED, existing.id());
            factStore.addRelation(existing.id(), saved.getId(), RelationType.CONTRADICTS);
            propagation.propagateFrom(saved.getId());
        }
        return saved;
    }

    /** Newest session wins; within a session, the later utterance wins. */
    private int compareRecency(OffsetDateTime aStart, int aSeq,
                               OffsetDateTime bStart, int bSeq) {
        if (aStart != null && bStart != null && !aStart.isEqual(bStart)) {
            return aStart.isAfter(bStart) ? 1 : -1;
        }
        return Integer.compare(aSeq, bSeq);
    }

    private boolean matchesRetracted(FactCandidate candidate) {
        try {
            return vectorSearch.topK(candidate.embedding(), 5,
                            List.of(FactStatus.RETRACTED)).stream()
                    .anyMatch(r -> r.similarity() >= REVIVAL_THRESHOLD);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private FactEntity saveNew(FactCandidate candidate, boolean needsReview) {
        return factStore.save(
                new FactStore.NewFact(candidate.statement(), candidate.type(),
                        candidate.confidence(), candidate.embedding(),
                        candidate.embeddingModel(), needsReview),
                candidate.provenanceUtteranceIds());
    }

    private OffsetDateTime candidateSessionStart(FactCandidate candidate) {
        if (candidate.sessionId() == null) {
            return null;
        }
        return sessions.findById(candidate.sessionId())
                .map(SessionEntity::getStartedAt).orElse(null);
    }

    private ProvenanceContext provenanceContext(java.util.UUID factId) {
        try {
            return jdbc.queryForObject(
                    "SELECT s.started_at, u.seq FROM fact_provenance fp "
                            + "JOIN utterance u ON u.id = fp.utterance_id "
                            + "JOIN session s ON s.id = u.session_id "
                            + "WHERE fp.fact_id = ? "
                            + "ORDER BY s.started_at DESC, u.seq DESC LIMIT 1",
                    (rs, n) -> new ProvenanceContext(
                            rs.getObject("started_at", OffsetDateTime.class),
                            rs.getInt("seq")),
                    factId);
        } catch (EmptyResultDataAccessException e) {
            return new ProvenanceContext(null, -1);
        }
    }
}
