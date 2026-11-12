-- Milestone 04B: durable provisioning intent, NOT execution.
-- Only APPROVED decisions can enqueue, with one tenant-scoped work item per request.
ALTER TABLE access_decisions
    ADD CONSTRAINT uq_access_decisions_request_outcome
    UNIQUE (tenant_id, request_id, decision);

CREATE TABLE provisioning_work_items (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    approval_outcome VARCHAR(16) NOT NULL DEFAULT 'APPROVED'
        CHECK (approval_outcome = 'APPROVED'),
    state VARCHAR(16) NOT NULL DEFAULT 'READY'
        CHECK (state = 'READY'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_provisioning_work_items PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT fk_provisioning_work_item_approved_decision
        FOREIGN KEY (tenant_id, request_id, approval_outcome)
        REFERENCES access_decisions (tenant_id, request_id, decision)
);

CREATE INDEX idx_provisioning_work_items_ready
    ON provisioning_work_items (created_at, tenant_id, request_id)
    WHERE state='READY';

-- Both insertions share the caller's PostgreSQL transaction. Failure to
-- enqueue an approved decision aborts its decision and status update.
CREATE FUNCTION iam_enqueue_approved_provisioning_work_item()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.decision = 'APPROVED' THEN
        INSERT INTO provisioning_work_items (tenant_id, request_id)
        VALUES (NEW.tenant_id, NEW.request_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enqueue_approved_provisioning_work_item
AFTER INSERT ON access_decisions
FOR EACH ROW EXECUTE FUNCTION iam_enqueue_approved_provisioning_work_item();

-- Migration compatibility: enqueue pre-existing APPROVED decisions once.
-- A new work item is only an intent; this migration never contacts a target.
INSERT INTO provisioning_work_items (tenant_id, request_id)
SELECT tenant_id, request_id FROM access_decisions WHERE decision='APPROVED'
ON CONFLICT (tenant_id, request_id) DO NOTHING;
