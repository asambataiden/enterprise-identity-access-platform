-- 04E-3: mock-target observation; distinct from simulated SUCCEEDED.
-- Append-once per attempt. No real external target is contacted by this migration.
CREATE TABLE provisioning_mock_observations (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    lease_token UUID NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('VERIFIED', 'ABSENT', 'UNKNOWN')),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_provisioning_mock_observations
        PRIMARY KEY (tenant_id,request_id,attempt_number),
    CONSTRAINT fk_provisioning_mock_observation_attempt
        FOREIGN KEY (tenant_id,request_id,attempt_number)
        REFERENCES provisioning_attempts(tenant_id,request_id,attempt_number)
);
