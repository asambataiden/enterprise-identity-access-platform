package dev.asambataiden.iam.accessrequest;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated approver binding; OAuth scope is not a substitute for this lookup. */
@Component
public class DecisionActorResolver {
    private final JdbcTemplate jdbc;

    public DecisionActorResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RequestActorResolver.Actor resolve(Jwt jwt) {
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
        UUID tenant;
        try {
            tenant = UUID.fromString(tenantClaim);
        } catch (IllegalArgumentException invalidTenant) {
            throw forbidden();
        }
        var actors = jdbc.query("""
            SELECT e.id FROM employee_identities i
              JOIN employees e ON e.tenant_id=i.tenant_id AND e.id=i.employee_id
              JOIN employee_permissions p ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
             WHERE i.tenant_id=? AND i.issuer=? AND i.subject=?
               AND e.lifecycle_status='ACTIVE'
               AND p.permission_key='ACCESS_REQUEST_DECIDE'
            """, (rs, row) -> rs.getObject(1, UUID.class),
                tenant, issuer, subject);
        if (actors.size() != 1) {
            throw forbidden();
        }
        return new RequestActorResolver.Actor(tenant, actors.getFirst());
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Approval not permitted");
    }
}
