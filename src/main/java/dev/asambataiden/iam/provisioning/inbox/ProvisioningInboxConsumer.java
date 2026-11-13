package dev.asambataiden.iam.provisioning.inbox;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicitly invoked, mock-only consumer. Does not provision an entitlement.
 * Callers must authenticate the transport and authorize message provenance before invoking.
 */
@Service
public class ProvisioningInboxConsumer {
    private final JdbcTemplate jdbc;

    public ProvisioningInboxConsumer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public record Message(UUID eventId, UUID tenantId, UUID requestId,
                          String eventType, int schemaVersion, String payload) {
        public Message {
            if (eventId == null || tenantId == null || requestId == null
                    || !"PROVISIONING_REQUESTED".equals(eventType)
                    || schemaVersion != 1 || payload == null || payload.isBlank()) {
                throw new IllegalArgumentException("Invalid provisioning event envelope");
            }
        }
    }

    public enum Result { APPLIED, DUPLICATE }

    /**
     * One transaction couples the idempotency receipt to the local projection.
     * Failures roll back both. Exact duplicate delivery is a successful no-op.
     * Reuse of an event identity with changed content is rejected.
     */
    @Transactional
    public Result consume(Message message) {
        Objects.requireNonNull(message, "message");
        List<UUID> inserted = jdbc.query("""
            INSERT INTO provisioning_inbox_receipts
                (event_id, tenant_id, request_id, event_type, schema_version, payload)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (event_id) DO NOTHING
            RETURNING event_id
            """, (rs, i) -> rs.getObject(1, UUID.class), message.eventId(),
            message.tenantId(), message.requestId(), message.eventType(),
            message.schemaVersion(), message.payload());

        if (!inserted.isEmpty()) {
            jdbc.update("""
                INSERT INTO provisioning_event_projections
                    (tenant_id, request_id, event_id, event_type)
                VALUES (?, ?, ?, ?)
                """, message.tenantId(), message.requestId(),
                message.eventId(), message.eventType());
            return Result.APPLIED;
        }

        Boolean equal = jdbc.queryForObject("""
            SELECT (tenant_id=? AND request_id=? AND event_type=?
                    AND schema_version=? AND payload=CAST(? AS jsonb))
              FROM provisioning_inbox_receipts WHERE event_id=?
            """, Boolean.class, message.tenantId(), message.requestId(),
            message.eventType(), message.schemaVersion(), message.payload(),
            message.eventId());
        if (!Boolean.TRUE.equals(equal)) {
            throw new IllegalStateException("Conflicting replay of provisioning event identity");
        }
        return Result.DUPLICATE;
    }
}
