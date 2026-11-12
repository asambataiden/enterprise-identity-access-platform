-- Milestone 04A: minimal, append-only decision audit evidence.
-- Additive migration: do not alter Flyway V1-V3. No provisioning or outbox.
CREATE TABLE approval_audit_events (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    requester_id UUID NOT NULL,
    approver_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL DEFAULT 'ACCESS_REQUEST_DECIDED'
        CHECK (event_type = 'ACCESS_REQUEST_DECIDED'),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPROVED', 'REJECTED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_approval_audit_decision UNIQUE (tenant_id, request_id),
    CONSTRAINT chk_approval_audit_sod CHECK (requester_id <> approver_id)
);

CREATE INDEX idx_approval_audit_tenant_time
    ON approval_audit_events(tenant_id, occurred_at DESC, event_id DESC);

-- The decision is inserted inside the existing @Transactional approval use case.
-- A trigger in the same PostgreSQL transaction avoids a second best-effort write.
CREATE FUNCTION iam_record_approval_audit() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO approval_audit_events
        (tenant_id, request_id, requester_id, approver_id, outcome, occurred_at)
    VALUES
        (NEW.tenant_id, NEW.request_id, NEW.requester_id,
         NEW.approver_id, NEW.decision, NEW.decided_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_approval_decision_audit
AFTER INSERT ON access_decisions
FOR EACH ROW EXECUTE FUNCTION iam_record_approval_audit();

-- Prevent ordinary SQL UPDATE/DELETE of audit rows, including via the app role.
-- DB owners/superusers may still bypass or replace a trigger: not WORM storage.
CREATE FUNCTION iam_reject_approval_audit_mutation() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Approval audit evidence is append-only'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_reject_approval_audit_mutation
BEFORE UPDATE OR DELETE ON approval_audit_events
FOR EACH ROW EXECUTE FUNCTION iam_reject_approval_audit_mutation();
