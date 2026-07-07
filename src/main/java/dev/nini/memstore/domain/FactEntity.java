package dev.nini.memstore.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An extracted fact. The {@code embedding} column (pgvector) is not mapped as a
 * JPA field; it is written and read through native SQL in the vector search
 * layer (Plan 03) to avoid a Hibernate custom type for the vector type.
 */
@Entity
@Table(name = "fact")
public class FactEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String statement;

    @Column(nullable = false)
    private FactType type;

    @Column(name = "embedding_model")
    private String embeddingModel;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false)
    private FactStatus status = FactStatus.ACTIVE;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_confirmed_at", nullable = false)
    private OffsetDateTime lastConfirmedAt;

    @Column(name = "invalidated_at")
    private OffsetDateTime invalidatedAt;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    protected FactEntity() {
    }

    public FactEntity(UUID id, String statement, FactType type, double confidence,
                      OffsetDateTime createdAt, OffsetDateTime lastConfirmedAt) {
        this.id = id;
        this.statement = statement;
        this.type = type;
        this.confidence = confidence;
        this.createdAt = createdAt;
        this.lastConfirmedAt = lastConfirmedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getStatement() {
        return statement;
    }

    public FactType getType() {
        return type;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public FactStatus getStatus() {
        return status;
    }

    public void setStatus(FactStatus status) {
        this.status = status;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(boolean needsReview) {
        this.needsReview = needsReview;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastConfirmedAt() {
        return lastConfirmedAt;
    }

    public void setLastConfirmedAt(OffsetDateTime lastConfirmedAt) {
        this.lastConfirmedAt = lastConfirmedAt;
    }

    public OffsetDateTime getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(OffsetDateTime invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }

    public UUID getSupersededBy() {
        return supersededBy;
    }

    public void setSupersededBy(UUID supersededBy) {
        this.supersededBy = supersededBy;
    }
}
