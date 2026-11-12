-- Milestone 04D: simulated, in-database entitlements only. No external adapter.
ALTER TABLE provisioning_work_items DROP CONSTRAINT chk_provisioning_work_state;
ALTER TABLE provisioning_work_items
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_provisioning_work_state
        CHECK (state IN ('READY','LEASED','RETRY_WAIT','QUARANTINED','SUCCEEDED')),
    ADD CONSTRAINT chk_provisioning_completion
        CHECK ((state='SUCCEEDED') = (completed_at IS NOT NULL));

CREATE TABLE simulated_entitlements (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    application_id UUID NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    originating_lease_token UUID NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_simulated_entitlements PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT fk_simulated_entitlements_work_item FOREIGN KEY (tenant_id, request_id)
        REFERENCES provisioning_work_items (tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT fk_simulated_entitlements_role FOREIGN KEY (tenant_id, application_id, role_key)
        REFERENCES application_roles (tenant_id, application_id, role_key),
    CONSTRAINT fk_simulated_entitlements_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES employees (tenant_id, id)
);

-- Guard direct SQL attempts to mark work succeeded without verified matching
-- synthetic evidence for the same request and currently held lease.
CREATE FUNCTION iam_guard_simulated_provisioning_success()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state = 'SUCCEEDED' AND OLD.state <> 'SUCCEEDED' THEN
        IF OLD.state <> 'LEASED'
           OR OLD.lease_token IS NULL
           OR OLD.lease_expires_at <= clock_timestamp()
           OR NEW.lease_token IS NOT NULL OR NEW.lease_expires_at IS NOT NULL
           OR NEW.attempt_count <> OLD.attempt_count
           OR NOT EXISTS (
               SELECT 1
                 FROM simulated_entitlements e
                 JOIN access_requests ar ON ar.tenant_id=e.tenant_id AND ar.id=e.request_id
                WHERE e.tenant_id=NEW.tenant_id AND e.request_id=NEW.request_id
                  AND ar.status='APPROVED'
                  AND e.employee_id=ar.employee_id
                  AND e.application_id=ar.application_id
                  AND e.role_key=ar.role_key
                  AND e.originating_lease_token=OLD.lease_token
           ) THEN
            RAISE EXCEPTION 'Verified synthetic entitlement and active lease required'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.state='SUCCEEDED' AND NEW.state <> 'SUCCEEDED' THEN
        RAISE EXCEPTION 'Completed provisioning is terminal' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_guard_simulated_provisioning_success
BEFORE UPDATE ON provisioning_work_items
FOR EACH ROW EXECUTE FUNCTION iam_guard_simulated_provisioning_success();
