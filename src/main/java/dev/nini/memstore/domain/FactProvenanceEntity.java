package dev.nini.memstore.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Many-to-many link between a fact and its source utterance. */
@Entity
@Table(name = "fact_provenance")
public class FactProvenanceEntity {

    @EmbeddedId
    private Key key;

    protected FactProvenanceEntity() {
    }

    public FactProvenanceEntity(UUID factId, UUID utteranceId) {
        this.key = new Key(factId, utteranceId);
    }

    public UUID getFactId() {
        return key.factId;
    }

    public UUID getUtteranceId() {
        return key.utteranceId;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "fact_id")
        private UUID factId;

        @Column(name = "utterance_id")
        private UUID utteranceId;

        protected Key() {
        }

        public Key(UUID factId, UUID utteranceId) {
            this.factId = factId;
            this.utteranceId = utteranceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(factId, other.factId)
                    && Objects.equals(utteranceId, other.utteranceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(factId, utteranceId);
        }
    }
}
