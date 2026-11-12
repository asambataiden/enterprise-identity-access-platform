-- Milestone 04E-1: write-ahead attempt evidence for future target adapters.
-- No HTTP calls, target mutations, or new SUCCEEDED transition.
CREATE TABLE provisioning_attempts (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 3),
    lease_token UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(trim(idempotency_key)) > 0),
    outcome VARCHAR(16) NOT NULL DEFAULT 'STARTED' CHECK (outcome='STARTED'),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_provisioning_attempts PRIMARY KEY (tenant_id, request_id, attempt_number),
    CONSTRAINT fk_provisioning_attempt_work_item
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES provisioning_work_items (tenant_id, request_id)
);

CREATE INDEX idx_provisioning_attempts_tenant_time
    ON provisioning_attempts (tenant_id, started_at DESC, request_id);
