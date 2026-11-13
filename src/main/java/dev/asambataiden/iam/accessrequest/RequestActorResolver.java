package dev.asambataiden.iam.accessrequest;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the authenticated subject to an active, permission-bearing local employee.
 * A tenant claim alone never provides authorization or creates a local identity.
 */
@Component
public class RequestActorResolver {
    private final JdbcTemplate jdbc;

    public RequestActorResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Actor resolve(Jwt jwt) {
        if (jwt == null) {
            throw forbidden();
        }
        var issuerUri = jwt.getIssuer();
        String subject = jwt.getSubject();
        if (issuerUri == null || subject == null || subject.isBlank()) {
            throw forbidden();
        }
        String issuer = issuerUri.toString();
        String tenantClaim;
        try {
            tenantClaim = jwt.getClaimAsString("tenant_id");
        } catch (RuntimeException invalidClaim) {
            throw forbidden();
        }
        if (tenantClaim == null || tenantClaim.isBlank()) {
            throw forbidden();
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantClaim);
        } catch (IllegalArgumentException invalidTenant) {
            throw forbidden();
        }
        var actors = jdbc.query("""
            SELECT e.id FROM employees e
            JOIN employee_identities identity
              ON identity.tenant_id = e.tenant_id AND identity.employee_id = e.id
            JOIN employee_permissions permission
              ON permission.tenant_id = e.tenant_id AND permission.employee_id = e.id
            WHERE e.tenant_id = ? AND identity.issuer = ? AND identity.subject = ?
              AND e.lifecycle_status = 'ACTIVE'
              AND permission.permission_key = 'ACCESS_REQUEST_CREATE'
            """, (rs, row) -> rs.getObject(1, UUID.class), tenantId, issuer, subject);

        if (actors.size() != 1) {
            throw forbidden();
        }
        return new Actor(tenantId, actors.getFirst());
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Access request not permitted");
    }

    public record Actor(UUID tenantId, UUID employeeId) {}
}
