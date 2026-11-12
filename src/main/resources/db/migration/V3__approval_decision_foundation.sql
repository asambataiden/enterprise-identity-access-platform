-- Milestone 03A: approval persistence foundation. No approval API or provisioning.
-- Additive Flyway migration; NEVER edit V1/V2 after application.
ALTER TABLE access_requests DROP CONSTRAINT access_requests_status_check;
ALTER TABLE access_requests ADD CONSTRAINT chk_access_requests_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
-- The composite key enables an approver decision to bind to the stored
-- original requester, making separation-of-duties DB-enforceable.
ALTER TABLE access_requests ADD CONSTRAINT uq_access_requests_tenant_id_requester
    UNIQUE (tenant_id, id, requester_id);

ALTER TABLE employee_permissions DROP CONSTRAINT employee_permissions_permission_key_check;
ALTER TABLE employee_permissions ADD CONSTRAINT chk_employee_permissions_key
    CHECK (permission_key IN ('ACCESS_REQUEST_CREATE', 'ACCESS_REQUEST_DECIDE'));

CREATE TABLE access_decisions (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    requester_id UUID NOT NULL,
    approver_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_access_decisions PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT chk_access_decisions_separation_of_duties CHECK (approver_id <> requester_id),
    CONSTRAINT fk_decision_request_owner FOREIGN KEY (tenant_id, request_id, requester_id)
        REFERENCES access_requests(tenant_id, id, requester_id),
    CONSTRAINT fk_decision_approver FOREIGN KEY (tenant_id, approver_id)
        REFERENCES employees(tenant_id, id)
);
CREATE INDEX idx_access_decisions_tenant_approver_decided
    ON access_decisions(tenant_id, approver_id, decided_at DESC);
