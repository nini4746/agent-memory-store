package dev.nini.memstore.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A directed relationship between two facts. */
@Entity
@Table(name = "fact_relation")
public class FactRelationEntity {

    @Id
    private UUID id;

    @Column(name = "from_fact_id", nullable = false)
    private UUID fromFactId;

    @Column(name = "to_fact_id", nullable = false)
    private UUID toFactId;

    @Column(nullable = false)
    private RelationType relation;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected FactRelationEntity() {
    }

    public FactRelationEntity(UUID id, UUID fromFactId, UUID toFactId,
                              RelationType relation, OffsetDateTime createdAt) {
        this.id = id;
        this.fromFactId = fromFactId;
        this.toFactId = toFactId;
        this.relation = relation;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFromFactId() {
        return fromFactId;
    }

    public UUID getToFactId() {
        return toFactId;
    }

    public RelationType getRelation() {
        return relation;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
