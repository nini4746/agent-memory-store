package dev.nini.memstore.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.nini.memstore.config.ConflictProperties;
import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.llm.LlmClient;
import dev.nini.memstore.llm.LlmClient.PairJudgment;
import dev.nini.memstore.repo.SessionRepository;
import dev.nini.memstore.search.FactVectorSearch;
import dev.nini.memstore.search.ScoredFactRow;
import dev.nini.memstore.service.FactStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the conflict-candidate window boundary (SPEC R4). Conflict detection only
 * inspects the top {@code candidate-limit} active neighbours whose similarity
 * clears {@code candidate-threshold}. These tests are pure Mockito (no Spring,
 * no Docker) so they always run in the local suite: the vector search is
 * stubbed to return exactly what a pgvector {@code LIMIT k} query would, which
 * lets us assert honestly that a contradiction at rank 6 is missed under the
 * historical top-5 default and caught once the window is widened.
 */
class ConflictWindowBoundaryTest {

    private static final String CANDIDATE = "The DB uses PostgreSQL 17";
    private static final String RANK6_CONTRADICTION = "The DB uses PostgreSQL 15";

    private final FactStore factStore = mock(FactStore.class);
    private final FactVectorSearch vectorSearch = mock(FactVectorSearch.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final PropagationService propagation = mock(PropagationService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @org.junit.jupiter.api.BeforeEach
    void defaultStubs() {
        // Registered before any test-body stub so test-specific overrides win by
        // Mockito's last-match rule: no retracted-fact matches, and every
        // classifyPair defaults to UNRELATED unless a test overrides a pair.
        lenient().when(vectorSearch.topK(any(), anyInt(), eq(List.of(FactStatus.RETRACTED))))
                .thenReturn(List.of());
        lenient().when(llm.classifyPair(any(), any())).thenReturn(PairJudgment.UNRELATED);
        FactEntity saved = mock(FactEntity.class, RETURNS_DEEP_STUBS);
        lenient().when(saved.getId()).thenReturn(UUID.randomUUID());
        lenient().when(factStore.save(any(), any())).thenReturn(saved);
    }

    private DefaultConflictResolver resolver(ConflictProperties props) {
        return new DefaultConflictResolver(factStore, vectorSearch, llm, sessions,
                propagation, jdbc, props);
    }

    private ConflictProperties props(int limit, double threshold) {
        ConflictProperties p = new ConflictProperties();
        p.setCandidateLimit(limit);
        p.setCandidateThreshold(threshold);
        return p;
    }

    private FactCandidate candidate() {
        return candidate(false);
    }

    private FactCandidate candidate(boolean explicitCorrection) {
        return new FactCandidate(CANDIDATE, FactType.DECISION, 0.9,
                new float[] {0.1f, 0.2f}, "fake-embed-1536",
                List.of(UUID.randomUUID()), null, 0, explicitCorrection);
    }

    /** Top-5 rows a pgvector {@code LIMIT 5} query returns; none contradict. */
    private List<ScoredFactRow> topFiveNonContradicting() {
        List<ScoredFactRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("unrelated neighbour " + i, 0.95 - i * 0.01));
        }
        return rows;
    }

    private ScoredFactRow row(String statement, double similarity) {
        return new ScoredFactRow(UUID.randomUUID(), statement, FactType.DECISION,
                0.9, FactStatus.ACTIVE, false, null, similarity);
    }

    @Test
    void contradictionAtRankSixIsMissedUnderDefaultTopFiveWindow() {
        // DOCUMENTED BUG BEHAVIOUR: a real contradiction exists but sits at rank
        // 6. With candidate-limit=5, pgvector's LIMIT 5 never returns it, so the
        // resolver classifies only the five closer (unrelated) neighbours and
        // stores the candidate as a brand-new active fact - the contradiction is
        // silently missed. No supersede/retract happens.
        when(vectorSearch.topK(any(), eq(5), eq(List.of(FactStatus.ACTIVE))))
                .thenReturn(topFiveNonContradicting());
        // The rank-6 fact WOULD be judged a contradiction, but it is never seen.
        lenient().when(llm.classifyPair(CANDIDATE, RANK6_CONTRADICTION))
                .thenReturn(PairJudgment.CONTRADICTS);

        ConflictResolver.Resolution result =
                resolver(props(5, 0.80)).handleNewFact(candidate());

        assertThat(result.createdNew()).isTrue();
        verify(factStore, times(1)).save(any(), any());
        verify(factStore, never()).transition(any(), any(), any());
        verify(factStore, never()).setNeedsReview(any(), eq(true));
        verify(factStore, never()).confirm(any());
        verify(llm, never()).classifyPair(CANDIDATE, RANK6_CONTRADICTION);
    }

    @Test
    void wideningTheWindowToSixCatchesTheRankSixContradiction() {
        // FIX VALUE: raising candidate-limit to 6 brings the rank-6 fact into the
        // window; it is classified CONTRADICTS and the target fact is resolved
        // (this candidate is an explicit correction, so the target is retracted).
        List<ScoredFactRow> topSix = new ArrayList<>(topFiveNonContradicting());
        ScoredFactRow rank6 = row(RANK6_CONTRADICTION, 0.895);
        topSix.add(rank6);
        when(vectorSearch.topK(any(), eq(6), eq(List.of(FactStatus.ACTIVE))))
                .thenReturn(topSix);
        when(llm.classifyPair(CANDIDATE, RANK6_CONTRADICTION))
                .thenReturn(PairJudgment.CONTRADICTS);

        resolver(props(6, 0.80)).handleNewFact(candidate(true));

        verify(factStore).transition(eq(rank6.id()), eq(FactStatus.RETRACTED), any());
    }

    @Test
    void neighbourBelowThresholdIsExcludedFromClassification() {
        // Threshold mechanics: a neighbour under candidate-threshold is filtered
        // out before the LLM ever classifies it.
        ScoredFactRow tooFar = row("below threshold neighbour", 0.79);
        when(vectorSearch.topK(any(), eq(5), eq(List.of(FactStatus.ACTIVE))))
                .thenReturn(List.of(tooFar));

        resolver(props(5, 0.80)).handleNewFact(candidate());

        verify(llm, never()).classifyPair(any(), eq("below threshold neighbour"));
        verify(factStore, times(1)).save(any(), any());
    }

    @Test
    void saturatedWindowEmitsTruncationWarning() {
        // When the window is full (all top-k cleared the threshold) the resolver
        // warns that a conflict may lie just outside it.
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultConflictResolver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(vectorSearch.topK(any(), eq(5), eq(List.of(FactStatus.ACTIVE))))
                    .thenReturn(topFiveNonContradicting());

            resolver(props(5, 0.80)).handleNewFact(candidate());

            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("window saturated"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
