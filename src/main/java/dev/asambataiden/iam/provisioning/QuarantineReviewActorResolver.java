package dev.asambataiden.iam.provisioning;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Verify issuer, subject, tenant and dedicated active employee permission. */
@Component
public class QuarantineReviewActorResolver {
    public record Actor(UUID tenantId, UUID employeeId) {}

    private final JdbcTemplate jdbc;

    public QuarantineReviewActorResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Actor resolve(Jwt jwt) {
        if (jwt == null || jwt.getIssuer() == null || jwt.getSubject() == null ||
                jwt.getSubject().isBlank()) {
            throw forbidden();
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        } catch (RuntimeException invalidClaim) {
            throw forbidden();
        }
        var ids = jdbc.query("""
            SELECT e.id
              FROM employee_identities i
              JOIN employees e ON e.tenant_id=i.tenant_id AND e.id=i.employee_id
              JOIN employee_permissions p ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
             WHERE i.tenant_id=? AND i.issuer=? AND i.subject=?
               AND e.lifecycle_status='ACTIVE'
               AND p.permission_key='PROVISIONING_QUARANTINE_REVIEW'
            """, (rs, row) -> rs.getObject(1, UUID.class),
                tenantId, jwt.getIssuer().toString(), jwt.getSubject());
        if (ids.size() != 1) {
            throw forbidden();
        }
        return new Actor(tenantId, ids.getFirst());
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Quarantine review not permitted");
    }
}
