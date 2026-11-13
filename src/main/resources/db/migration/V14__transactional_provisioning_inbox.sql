-- 04G-3: mock-only transactional consumer receipt and read-model projection.
-- Additive migration; no retroactive replay and no actual entitlement writes.
  CREATE TABLE provisioning_inbox_receipts (
                                             event_id UUID PRIMARY KEY,
                                             tenant_id UUID NOT NULL,
                                             request_id UUID NOT NULL,
                                             event_type VARCHAR(64) NOT NULL
                                               CHECK (event_type = 'PROVISIONING_REQUESTED'),
                                             schema_version SMALLINT NOT NULL CHECK (schema_version = 1),
                                             payload JSONB NOT NULL,
                                             received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                             CONSTRAINT uq_inbox_business_intent UNIQUE (tenant_id, request_id, event_type),
                                             CONSTRAINT uq_inbox_receipt_identity UNIQUE (tenant_id, event_id),
                                             CONSTRAINT chk_inbox_envelope CHECK ((
                                               jsonb_typeof(payload) = 'object'
                                                 AND payload->>'tenantId' = tenant_id::text
                                                 AND payload->>'requestId' = request_id::text
                                                 AND payload->>'eventType' = event_type
                                                 AND payload->>'schemaVersion' = schema_version::text
                                               ) IS TRUE)
  );

-- A safe local projection, not a grant, successful provisioning, or broker ACK.
  CREATE TABLE provisioning_event_projections (
                                                tenant_id UUID NOT NULL,
                                                request_id UUID NOT NULL,
                                                event_id UUID NOT NULL,
                                                event_type VARCHAR(64) NOT NULL
                                                  CHECK (event_type = 'PROVISIONING_REQUESTED'),
                                                projection_state VARCHAR(16) NOT NULL DEFAULT 'RECEIVED'
                                                  CHECK (projection_state = 'RECEIVED'),
                                                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                CONSTRAINT pk_provisioning_event_projection PRIMARY KEY (tenant_id, request_id),
                                                CONSTRAINT fk_projection_inbox_receipt FOREIGN KEY (tenant_id, event_id)
                                                  REFERENCES provisioning_inbox_receipts (tenant_id, event_id)
  );
