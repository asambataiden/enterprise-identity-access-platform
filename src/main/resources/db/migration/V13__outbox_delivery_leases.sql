-- 04G-2: durable, fenced outbox dispatch coordination (no broker or scheduler).
-- Do not alter historical V12 checksums or enqueue historical events.
ALTER TABLE provisioning_outbox_events
    DROP CONSTRAINT provisioning_outbox_events_delivery_status_check;

ALTER TABLE provisioning_outbox_events
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN delivery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(32),
    ADD CONSTRAINT chk_outbox_delivery_status CHECK
        (delivery_status IN ('PENDING', 'IN_FLIGHT', 'RETRY_WAIT', 'DELIVERED', 'DEAD_LETTER')),
    ADD CONSTRAINT chk_outbox_lease_fields CHECK
        ((delivery_status = 'IN_FLIGHT') =
            (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    ADD CONSTRAINT chk_outbox_delivery_attempts CHECK
        (delivery_attempts BETWEEN 0 AND 5),
    ADD CONSTRAINT chk_outbox_delivered_at CHECK
        ((delivery_status = 'DELIVERED') = (delivered_at IS NOT NULL)),
    ADD CONSTRAINT chk_outbox_last_error CHECK
        (last_error_code IS NULL OR last_error_code IN
            ('PUBLISH_UNAVAILABLE', 'LEASE_EXPIRED', 'UNKNOWN'));

CREATE INDEX idx_outbox_dispatch_eligible
    ON provisioning_outbox_events (next_attempt_at, created_at, event_id)
    WHERE delivery_status IN ('PENDING', 'RETRY_WAIT');

CREATE INDEX idx_outbox_dispatch_expired
    ON provisioning_outbox_events (lease_expires_at, event_id)
    WHERE delivery_status = 'IN_FLIGHT';
