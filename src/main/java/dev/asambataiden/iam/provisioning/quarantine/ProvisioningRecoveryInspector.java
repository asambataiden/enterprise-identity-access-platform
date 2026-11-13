package dev.asambataiden.iam.provisioning.quarantine;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal read-only inspection. No API endpoint, requeue, lease acquisition,
 * permission grant, remediation or target-system access.
 */
@Service
public class ProvisioningRecoveryInspector {
    private final JdbcTemplate jdbc;

    public ProvisioningRecoveryInspector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(
            UUID tenantId,
            UUID requestId,
            String state,
            int attempts,
            String lastErrorCode,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime leaseExpiresAt,
            int startedAttempts,
            int mockVerified,
            int mockAbsent,
            int mockUnknown) {}

    @Transactional(readOnly = true)
    public Optional<Snapshot> inspect(UUID tenantId, UUID requestId) {
        if (tenantId == null || requestId == null) {
            throw new IllegalArgumentException("Tenant and request are required");
        }
        List<Snapshot> found = jdbc.query("""
            SELECT w.tenant_id,w.request_id,w.state,w.attempt_count,
                   w.last_error_code,w.next_attempt_at,w.lease_expires_at,
                   (SELECT count(*) FROM provisioning_attempts a
                     WHERE a.tenant_id=w.tenant_id AND a.request_id=w.request_id)
                       AS started_attempts,
                   (SELECT count(*) FROM provisioning_mock_observations o
                     WHERE o.tenant_id=w.tenant_id AND o.request_id=w.request_id
                       AND o.outcome='VERIFIED') AS mock_verified,
                   (SELECT count(*) FROM provisioning_mock_observations o
                     WHERE o.tenant_id=w.tenant_id AND o.request_id=w.request_id
                       AND o.outcome='ABSENT') AS mock_absent,
                   (SELECT count(*) FROM provisioning_mock_observations o
                     WHERE o.tenant_id=w.tenant_id AND o.request_id=w.request_id
                       AND o.outcome='UNKNOWN') AS mock_unknown
              FROM provisioning_work_items w
             WHERE w.tenant_id=? AND w.request_id=?
            """, (rs, i) -> new Snapshot(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("state"),
                rs.getInt("attempt_count"),
                rs.getString("last_error_code"),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                rs.getObject("lease_expires_at", OffsetDateTime.class),
                rs.getInt("started_attempts"),
                rs.getInt("mock_verified"),
                rs.getInt("mock_absent"),
                rs.getInt("mock_unknown")), tenantId, requestId);
        return found.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Snapshot> quarantined(UUID tenantId, int limit) {
        if (tenantId == null || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Tenant and limit (1..100) required");
        }
        // No cross-tenant fallback; query only bounded stable identifiers.
        List<UUID> ids = jdbc.query("""
            SELECT request_id FROM provisioning_work_items
             WHERE tenant_id=? AND state='QUARANTINED'
             ORDER BY created_at,request_id
             LIMIT ?
            """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, limit);
        return ids.stream().map(id -> inspect(tenantId, id).orElseThrow()).toList();
    }
}
