package dev.nini.memstore.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A raw utterance within a session. Append-only; the database rejects any
 * UPDATE or DELETE (see V3 trigger), so this entity is effectively immutable.
 */
@Entity
@Table(name = "utterance")
public class UtteranceEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UtteranceEntity() {
    }

    public UtteranceEntity(UUID id, UUID sessionId, int seq, String role,
                           String content, OffsetDateTime createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.seq = seq;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public int getSeq() {
        return seq;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
