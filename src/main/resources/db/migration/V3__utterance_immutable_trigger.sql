-- Enforce the append-only invariant on utterance rows (SPEC N1).
-- Any UPDATE or DELETE against utterance is rejected at the database level so
-- the original session log can never be mutated by any code path.

CREATE OR REPLACE FUNCTION block_utterance_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'utterance rows are immutable (append-only log); % is not permitted',
        TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_utterance_no_update
    BEFORE UPDATE ON utterance
    FOR EACH ROW EXECUTE FUNCTION block_utterance_mutation();

CREATE TRIGGER trg_utterance_no_delete
    BEFORE DELETE ON utterance
    FOR EACH ROW EXECUTE FUNCTION block_utterance_mutation();
