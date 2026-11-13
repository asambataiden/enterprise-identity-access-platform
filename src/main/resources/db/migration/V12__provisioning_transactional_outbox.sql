-- 04G-1: durable intent-to-event atomicity. No broker or dispatcher in this migration.
-- Historical work items are deliberately NOT backfilled; provenance/replay
-- policy must be designed and approved separately.
  CREATE TABLE provisioning_outbox_events (
                                            event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                            tenant_id UUID NOT NULL,
                                            request_id UUID NOT NULL,
                                            event_type VARCHAR(64) NOT NULL DEFAULT 'PROVISIONING_REQUESTED'
                                              CHECK (event_type = 'PROVISIONING_REQUESTED'),
                                            schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version = 1),
                                            payload JSONB NOT NULL,
                                            delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                                              CHECK (delivery_status = 'PENDING'),
                                            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                            CONSTRAINT uq_provisioning_outbox_intent UNIQUE (tenant_id, request_id, event_type),
                                            CONSTRAINT chk_provisioning_outbox_payload CHECK ((
                                              jsonb_typeof(payload) = 'object'
                                                AND payload->>'tenantId' = tenant_id::text
                                                AND payload->>'requestId' = request_id::text
                                                AND payload->>'eventType' = event_type
                                                AND payload->>'schemaVersion' = schema_version::text
                                              ) IS TRUE)
  );

  CREATE INDEX idx_provisioning_outbox_pending
    ON provisioning_outbox_events (created_at, event_id)
    WHERE delivery_status = 'PENDING';

  -- An AFTER INSERT trigger executes in the same transaction as V5's
-- approved-decision -> work-item trigger; any outbox failure rolls back all.
  CREATE FUNCTION iam_enqueue_provisioning_outbox_event()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
  BEGIN
    INSERT INTO provisioning_outbox_events
      (tenant_id, request_id, event_type, schema_version, payload)
    VALUES (
             NEW.tenant_id, NEW.request_id, 'PROVISIONING_REQUESTED', 1,
             jsonb_build_object(
               'tenantId', NEW.tenant_id::text,
               'requestId', NEW.request_id::text,
               'eventType', 'PROVISIONING_REQUESTED',
               'schemaVersion', 1
             )
           );
    RETURN NEW;
  END;
  $$;

  CREATE TRIGGER trg_enqueue_provisioning_outbox_event
    AFTER INSERT ON provisioning_work_items
    FOR EACH ROW EXECUTE FUNCTION iam_enqueue_provisioning_outbox_event();
