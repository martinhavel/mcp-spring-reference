-- Append-only audit trail of every governed tool call, including denied and failed ones.
CREATE TABLE audit_event (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    trace_id      VARCHAR(64)  NOT NULL,
    principal     VARCHAR(128) NOT NULL,
    roles         VARCHAR(256) NOT NULL,
    tool          VARCHAR(64)  NOT NULL,
    parameters    JSONB        NOT NULL,
    outcome       VARCHAR(32)  NOT NULL
        CHECK (outcome IN ('SUCCESS', 'UPSTREAM_ERROR', 'DENIED', 'INVALID_INPUT', 'RATE_LIMITED', 'UNAVAILABLE')),
    result_sha256 CHAR(64),
    detail        VARCHAR(512)
);

CREATE INDEX audit_event_trace_id_idx ON audit_event (trace_id);
CREATE INDEX audit_event_occurred_at_idx ON audit_event (occurred_at);

-- Defence in depth: even the table owner cannot rewrite history through normal DML.
-- (A superuser can still disable triggers; ship the table to WORM storage / SIEM for that threat.)
CREATE FUNCTION audit_event_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% rejected)', TG_OP;
END;
$$;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_reject_change();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_reject_change();
