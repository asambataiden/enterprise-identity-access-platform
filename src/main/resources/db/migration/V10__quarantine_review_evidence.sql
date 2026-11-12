-- Milestone 04F-2: informational, append-only-by-service review evidence.
-- This table grants NO remediation authority and changes NO provisioning states.
CREATE TABLE provisioning_quarantine_reviews (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    review_id UUID NOT NULL,
    reviewer_employee_id UUID NOT NULL,
    decision VARCHAR(20) NOT NULL
        CHECK (decision IN ('HOLD', 'ESCALATE', 'RECONCILE_REQUIRED')),
    reason_code VARCHAR(32) NOT NULL
        CHECK (reason_code IN ('UNCERTAIN_TARGET_STATE',
                              'POSSIBLE_DUPLICATE',
                              'POLICY_REVIEW',
                              'DEPENDENCY_UNAVAILABLE')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_provisioning_quarantine_reviews
        PRIMARY KEY (tenant_id, request_id, review_id),
    CONSTRAINT fk_quarantine_review_work_item FOREIGN KEY (tenant_id, request_id)
        REFERENCES provisioning_work_items (tenant_id, request_id),
    CONSTRAINT fk_quarantine_review_employee FOREIGN KEY (tenant_id, reviewer_employee_id)
        REFERENCES employees (tenant_id, id)
);
CREATE INDEX idx_quarantine_review_history
    ON provisioning_quarantine_reviews (tenant_id, request_id, recorded_at DESC, review_id);
