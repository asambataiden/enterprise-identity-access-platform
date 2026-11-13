package dev.asambataiden.iam.provisioning.outbox;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, at-least-once delivery coordination; never performs network I/O. */
@Service
public class OutboxDeliveryService {
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbc;

    public OutboxDeliveryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Delivery(UUID eventId, UUID tenantId, UUID requestId,
                           String eventType, int schemaVersion, String payload,
                           UUID leaseToken, int attempt, OffsetDateTime leaseExpiresAt) {}

    public enum FailureCode { PUBLISH_UNAVAILABLE, UNKNOWN }

    /** Claim one event across all tenants. Intended only for a trusted internal relay. */
    @Transactional
    public Optional<Delivery> claim(int leaseSeconds) {
        if (leaseSeconds < 5 || leaseSeconds > 300) {
            throw new IllegalArgumentException("Lease duration must be 5..300 seconds");
        }
        // A worker that crashed holding its fifth lease cannot strand an event.
        jdbc.update("""
            UPDATE provisioning_outbox_events
               SET delivery_status='DEAD_LETTER', lease_token=NULL,
                   lease_expires_at=NULL, last_error_code='LEASE_EXPIRED'
             WHERE delivery_status='IN_FLIGHT' AND lease_expires_at<=CURRENT_TIMESTAMP
               AND delivery_attempts >= ?
            """, MAX_ATTEMPTS);

        var records = jdbc.query("""
            WITH candidate AS (
                SELECT event_id FROM provisioning_outbox_events
                 WHERE (delivery_status IN ('PENDING','RETRY_WAIT')
                         AND next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (delivery_status='IN_FLIGHT'
                         AND lease_expires_at <= CURRENT_TIMESTAMP
                         AND delivery_attempts < ?)
                 ORDER BY created_at,event_id
                 LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE provisioning_outbox_events AS o
               SET delivery_status='IN_FLIGHT', lease_token=gen_random_uuid(),
                   lease_expires_at=CURRENT_TIMESTAMP + make_interval(secs => ?),
                   delivery_attempts=delivery_attempts+1,
                   last_error_code=NULL
              FROM candidate c
             WHERE o.event_id=c.event_id
            RETURNING o.event_id,o.tenant_id,o.request_id,o.event_type,
                      o.schema_version,o.payload::text AS payload,
                      o.lease_token,o.delivery_attempts,o.lease_expires_at
            """, (rs, i) -> new Delivery(
                rs.getObject("event_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("event_type"), rs.getInt("schema_version"),
                rs.getString("payload"), rs.getObject("lease_token", UUID.class),
                rs.getInt("delivery_attempts"),
                rs.getObject("lease_expires_at", OffsetDateTime.class)),
            MAX_ATTEMPTS, leaseSeconds);
        return records.stream().findFirst();
    }

    /** Only the currently valid lease may acknowledge delivery. */
    @Transactional
    public boolean delivered(Delivery delivery) {
        requireDelivery(delivery);
        return jdbc.update("""
            UPDATE provisioning_outbox_events
               SET delivery_status='DELIVERED', delivered_at=CURRENT_TIMESTAMP,
                   lease_token=NULL, lease_expires_at=NULL, last_error_code=NULL
             WHERE event_id=? AND tenant_id=? AND delivery_status='IN_FLIGHT'
               AND lease_token=? AND lease_expires_at>CURRENT_TIMESTAMP
            """, delivery.eventId(), delivery.tenantId(), delivery.leaseToken()) == 1;
    }

    /** Explicit retry/backoff; the fifth failed attempt becomes dead-letter. */
    @Transactional
    public boolean fail(Delivery delivery, FailureCode code, int retryDelaySeconds) {
        requireDelivery(delivery);
        if (code == null || retryDelaySeconds < 1 || retryDelaySeconds > 3600) {
            throw new IllegalArgumentException("Invalid failure code or delay");
        }
        return jdbc.update("""
            UPDATE provisioning_outbox_events
               SET delivery_status=CASE WHEN delivery_attempts>=? THEN 'DEAD_LETTER'
                                        ELSE 'RETRY_WAIT' END,
                   lease_token=NULL, lease_expires_at=NULL,
                   next_attempt_at=CURRENT_TIMESTAMP + make_interval(secs => ?),
                   last_error_code=?
             WHERE event_id=? AND tenant_id=? AND delivery_status='IN_FLIGHT'
               AND lease_token=? AND lease_expires_at>CURRENT_TIMESTAMP
            """, MAX_ATTEMPTS, retryDelaySeconds, code.name(),
            delivery.eventId(), delivery.tenantId(), delivery.leaseToken()) == 1;
    }

    private static void requireDelivery(Delivery delivery) {
        if (delivery == null || delivery.eventId() == null ||
                delivery.tenantId() == null || delivery.leaseToken() == null ||
                delivery.attempt() < 1) {
            throw new IllegalArgumentException("Invalid delivery lease");
        }
    }
}
