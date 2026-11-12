-- Milestone 04C: database-coordinated work leasing, never external execution.
ALTER TABLE provisioning_work_items
    DROP CONSTRAINT provisioning_work_items_state_check;

ALTER TABLE provisioning_work_items
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_error_code VARCHAR(32),
    ADD CONSTRAINT chk_provisioning_work_state
        CHECK (state IN ('READY', 'LEASED', 'RETRY_WAIT', 'QUARANTINED')),
    ADD CONSTRAINT chk_provisioning_lease_fields
        CHECK ((state = 'LEASED') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    ADD CONSTRAINT chk_provisioning_attempt_count
        CHECK (attempt_count BETWEEN 0 AND 3),
    ADD CONSTRAINT chk_provisioning_error_code
        CHECK (last_error_code IS NULL OR last_error_code IN
          ('TEMPORARY_DEPENDENCY_FAILURE', 'TIMEOUT', 'UNKNOWN'));

-- One worker locks and claims a row at a time with FOR UPDATE SKIP LOCKED.
-- The lease token fences stale workers; expiry by itself does not grant success.
CREATE INDEX idx_provisioning_eligible
    ON provisioning_work_items(tenant_id, next_attempt_at, created_at, request_id)
    WHERE state IN ('READY','RETRY_WAIT');
CREATE INDEX idx_provisioning_expired_leases
    ON provisioning_work_items(tenant_id, lease_expires_at)
    WHERE state='LEASED';
