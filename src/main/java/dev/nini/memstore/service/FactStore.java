package dev.nini.memstore.service;

import dev.nini.memstore.domain.FactEntity;
import dev.nini.memstore.domain.FactProvenanceEntity;
import dev.nini.memstore.domain.FactRelationEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.FactType;
import dev.nini.memstore.domain.RelationType;
import dev.nini.memstore.domain.UtteranceEntity;
import dev.nini.memstore.repo.FactProvenanceRepository;
import dev.nini.memstore.repo.FactRelationRepository;
import dev.nini.memstore.repo.FactRepository;
import dev.nini.memstore.repo.UtteranceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence facade for facts, provenance and relations (SPEC R1). Enforces
 * the provenance invariant (SPEC N4): a fact cannot be created without at least
 * one source utterance link. The pgvector embedding column is written through
 * native SQL because it is not mapped as a JPA field.
 */
@Service
public class FactStore {

    private static final double CONFIRM_STEP = 0.1;
    private static final double CONFIRM_CAP = 0.95;

    /** Input for creating a new fact. Embedding may be null in early stages. */
    public record NewFact(String statement, FactType type, double confidence,
                          float[] embedding, String embeddingModel,
                          boolean needsReview) {
    }

    private final FactRepository facts;
    private final FactProvenanceRepository provenance;
    private final FactRelationRepository relations;
    private final UtteranceRepository utterances;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FactStore(FactRepository facts, FactProvenanceRepository provenance,
                     FactRelationRepository relations, UtteranceRepository utterances,
                     JdbcTemplate jdbc, Clock clock) {
        this.facts = facts;
        this.provenance = provenance;
        this.relations = relations;
        this.utterances = utterances;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public FactEntity save(NewFact newFact, List<UUID> provenanceUtteranceIds) {
        return saveAt(newFact, provenanceUtteranceIds, OffsetDateTime.now(clock));
    }

    /** Saves a fact with an explicit creation/confirmation timestamp (seeding). */
    @Transactional
    public FactEntity saveAt(NewFact newFact, List<UUID> provenanceUtteranceIds,
                             OffsetDateTime timestamp) {
        if (provenanceUtteranceIds == null || provenanceUtteranceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "a fact requires at least one provenance utterance (SPEC N4)");
        }
        FactEntity fact = new FactEntity(UUID.randomUUID(), newFact.statement(),
                newFact.type(), newFact.confidence(), timestamp, timestamp);
        fact.setNeedsReview(newFact.needsReview());
        fact.setEmbeddingModel(newFact.embeddingModel());
        facts.save(fact);

        if (newFact.embedding() != null) {
            writeEmbedding(fact.getId(), newFact.embedding());
        }
        for (UUID utteranceId : provenanceUtteranceIds) {
            provenance.save(new FactProvenanceEntity(fact.getId(), utteranceId));
        }
        return fact;
    }

    private void writeEmbedding(UUID factId, float[] embedding) {
        jdbc.update("UPDATE fact SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorLiteral(embedding), factId);
    }

    static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Transitions a fact to a non-active status, recording invalidated_at and an
     * optional superseded_by pointer.
     */
    @Transactional
    public FactEntity transition(UUID factId, FactStatus newStatus, UUID supersededBy) {
        FactEntity fact = facts.findById(factId).orElseThrow(
                () -> new IllegalArgumentException("no such fact: " + factId));
        fact.setStatus(newStatus);
        if (newStatus != FactStatus.ACTIVE && fact.getInvalidatedAt() == null) {
            fact.setInvalidatedAt(OffsetDateTime.now(clock));
        }
        if (supersededBy != null) {
            fact.setSupersededBy(supersededBy);
        }
        return facts.save(fact);
    }

    /**
     * Confirms an existing fact on re-extraction: bumps confidence by 0.1 up to a
     * 0.95 cap and refreshes last_confirmed_at (SPEC R2).
     */
    @Transactional
    public FactEntity confirm(UUID factId) {
        FactEntity fact = facts.findById(factId).orElseThrow(
                () -> new IllegalArgumentException("no such fact: " + factId));
        fact.setConfidence(Math.min(CONFIRM_CAP, fact.getConfidence() + CONFIRM_STEP));
        fact.setLastConfirmedAt(OffsetDateTime.now(clock));
        return facts.save(fact);
    }

    @Transactional
    public void setNeedsReview(UUID factId, boolean needsReview) {
        facts.findById(factId).ifPresent(fact -> {
            fact.setNeedsReview(needsReview);
            facts.save(fact);
        });
    }

    @Transactional
    public FactRelationEntity addRelation(UUID fromFactId, UUID toFactId,
                                          RelationType relation) {
        FactRelationEntity entity = new FactRelationEntity(UUID.randomUUID(),
                fromFactId, toFactId, relation, OffsetDateTime.now(clock));
        return relations.save(entity);
    }

    @Transactional(readOnly = true)
    public Optional<FactEntity> find(UUID factId) {
        return facts.findById(factId);
    }

    @Transactional(readOnly = true)
    public List<FactEntity> findByStatus(FactStatus status) {
        return facts.findByStatus(status);
    }

    /** Reverse lookup from a fact to its source utterances (SPEC R1-A4). */
    @Transactional(readOnly = true)
    public List<UtteranceEntity> provenanceUtterances(UUID factId) {
        List<UtteranceEntity> result = new ArrayList<>();
        for (FactProvenanceEntity link : provenance.findByKeyFactId(factId)) {
            utterances.findById(link.getUtteranceId()).ifPresent(result::add);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<FactRelationEntity> relationsFrom(UUID factId) {
        return relations.findByFromFactId(factId);
    }

    @Transactional(readOnly = true)
    public List<FactRelationEntity> relationsTo(UUID factId) {
        return relations.findByToFactId(factId);
    }
}
