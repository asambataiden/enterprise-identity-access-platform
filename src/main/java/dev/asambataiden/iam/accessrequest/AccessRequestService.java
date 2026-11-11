package dev.asambataiden.iam.accessrequest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal persistence use case only. Caller identity and authorization must be
 * enforced by the future authenticated application/API boundary.
 */
@Service
public class AccessRequestService {
    private final JdbcTemplate jdbc;

    public AccessRequestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AccessRequest create(
            UUID tenantId, UUID requesterId, UUID employeeId,
            UUID applicationId, String roleKey, String idempotencyKey
    ) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(applicationId, "applicationId");
        requireBounded(roleKey, 64, "roleKey");
        requireBounded(idempotencyKey, 128, "idempotencyKey");

        // Checks improve diagnostics; the composite foreign keys remain the
        // authoritative protection against cross-tenant or nonexistent rows.
        Integer eligible = jdbc.queryForObject("""
            SELECT COUNT(*) FROM employees actor
            JOIN employees subject ON actor.tenant_id = subject.tenant_id
            JOIN applications app ON app.tenant_id = subject.tenant_id
            JOIN application_roles role
              ON role.tenant_id = app.tenant_id AND role.application_id = app.id
            WHERE actor.tenant_id = ? AND actor.id = ? AND actor.lifecycle_status = 'ACTIVE'
              AND subject.id = ? AND subject.lifecycle_status = 'ACTIVE'
              AND app.id = ? AND app.lifecycle_status = 'ACTIVE'
              AND role.role_key = ?
            """, Integer.class, tenantId, requesterId, employeeId, applicationId, roleKey);
        if (eligible == null || eligible != 1) {
            throw new IllegalArgumentException("Invalid tenant-local access request references");
        }

        jdbc.update("""
            INSERT INTO access_requests
                (id, tenant_id, requester_id, employee_id, application_id, role_key, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """, UUID.randomUUID(), tenantId, requesterId, employeeId, applicationId, roleKey, idempotencyKey);

        AccessRequest stored = jdbc.queryForObject("""
            SELECT id, tenant_id, requester_id, employee_id, application_id,
                   role_key, idempotency_key, status, created_at
              FROM access_requests
             WHERE tenant_id = ? AND idempotency_key = ?
            """, AccessRequestService::map, tenantId, idempotencyKey);
        if (stored == null) {
            throw new IllegalStateException("Access request missing after insert-or-find");
        }
        if (!stored.requesterId().equals(requesterId)
                || !stored.employeeId().equals(employeeId)
                || !stored.applicationId().equals(applicationId)
                || !stored.roleKey().equals(roleKey)) {
            throw new AccessRequestConflictException();
        }
        return stored;
    }

    private static void requireBounded(String value, int max, String name) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > max) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private static AccessRequest map(ResultSet rs, int row) throws SQLException {
        return new AccessRequest(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("requester_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("application_id", UUID.class),
                rs.getString("role_key"),
                rs.getString("idempotency_key"),
                rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class));
    }
}
