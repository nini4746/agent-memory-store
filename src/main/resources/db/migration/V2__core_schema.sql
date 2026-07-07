-- Core schema for the agent-native memory store (SPEC R1).
-- session -> utterance (append-only log) -> fact (extracted) with provenance
-- links and inter-fact relations.

CREATE TABLE session (
    id          uuid PRIMARY KEY,
    source      text,
    started_at  timestamptz NOT NULL DEFAULT now(),
    ended_at    timestamptz,
    metadata    jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE utterance (
    id          uuid PRIMARY KEY,
    session_id  uuid NOT NULL REFERENCES session(id),
    seq         integer NOT NULL,
    role        text NOT NULL,
    content     text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq)
);
CREATE INDEX idx_utterance_session_seq ON utterance(session_id, seq);

CREATE TABLE fact (
    id                uuid PRIMARY KEY,
    statement         text NOT NULL,
    type              text NOT NULL
        CHECK (type IN ('preference', 'constraint', 'decision', 'event', 'entity')),
    embedding         vector(1536),
    embedding_model   text,
    confidence        double precision NOT NULL,
    status            text NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'superseded', 'retracted', 'expired')),
    needs_review      boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    last_confirmed_at timestamptz NOT NULL DEFAULT now(),
    invalidated_at    timestamptz,
    superseded_by     uuid REFERENCES fact(id)
);
CREATE INDEX idx_fact_status ON fact(status);

CREATE TABLE fact_provenance (
    fact_id      uuid NOT NULL REFERENCES fact(id),
    utterance_id uuid NOT NULL REFERENCES utterance(id),
    PRIMARY KEY (fact_id, utterance_id)
);
CREATE INDEX idx_fact_provenance_utterance ON fact_provenance(utterance_id);
CREATE INDEX idx_fact_provenance_fact ON fact_provenance(fact_id);

CREATE TABLE fact_relation (
    id           uuid PRIMARY KEY,
    from_fact_id uuid NOT NULL REFERENCES fact(id),
    to_fact_id   uuid NOT NULL REFERENCES fact(id),
    relation     text NOT NULL
        CHECK (relation IN ('contradicts', 'supersedes', 'refines', 'duplicates')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (from_fact_id, to_fact_id, relation)
);
CREATE INDEX idx_fact_relation_from ON fact_relation(from_fact_id);
CREATE INDEX idx_fact_relation_to ON fact_relation(to_fact_id);
