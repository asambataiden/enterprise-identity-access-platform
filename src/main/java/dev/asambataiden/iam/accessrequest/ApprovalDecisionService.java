package dev.asambataiden.iam.accessrequest;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal approval use case. Only server-resolved authenticated identities may
 * call this service from a future API boundary. No provisioning side effects.
 */
@Service
public class ApprovalDecisionService {
    private final JdbcTemplate jdbc;

    public ApprovalDecisionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Decision { APPROVED, REJECTED }

    /**
     * One transaction serializes competing decisions on the access request.
     * All failures roll back the status update and decision insertion together.
     */
    @Transactional
    public void decide(UUID tenantId, UUID approverId, UUID requestId, Decision decision) {
        if (tenantId == null || approverId == null || requestId == null || decision == null) {
            throw new IllegalArgumentException("Invalid decision parameters");
        }

        Integer authorized = jdbc.queryForObject("""
            SELECT COUNT(*) FROM employees e
            JOIN employee_permissions p
              ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
            WHERE e.tenant_id=? AND e.id=? AND e.lifecycle_status='ACTIVE'
              AND p.permission_key='ACCESS_REQUEST_DECIDE'
            """, Integer.class, tenantId, approverId);
        if (authorized == null || authorized != 1) {
            throw new SecurityException("Decision not permitted");
        }

        // The row lock prevents two approvers from both observing PENDING.
        var records = jdbc.query("""
            SELECT ar.requester_id, ar.status
              FROM access_requests ar
              JOIN employees subject ON subject.tenant_id=ar.tenant_id AND subject.id=ar.employee_id
              JOIN applications app ON app.tenant_id=ar.tenant_id AND app.id=ar.application_id
              JOIN application_roles role ON role.tenant_id=ar.tenant_id
                   AND role.application_id=ar.application_id AND role.role_key=ar.role_key
             WHERE ar.tenant_id=? AND ar.id=?
               AND subject.lifecycle_status='ACTIVE'
               AND app.lifecycle_status='ACTIVE'
             FOR UPDATE OF ar
            """, (rs, rowNum) -> new RequestState(
                rs.getObject("requester_id", UUID.class), rs.getString("status")), tenantId, requestId);
        if (records.size() != 1) {
            throw new IllegalArgumentException("Decision target unavailable");
        }

        RequestState request = records.getFirst();
        if (request.requesterId().equals(approverId)) {
            throw new SecurityException("Self-approval prohibited");
        }
        if (!"PENDING".equals(request.status())) {
            throw new IllegalStateException("Request already decided");
        }

        int updated = jdbc.update("""
            UPDATE access_requests SET status=?
             WHERE tenant_id=? AND id=? AND status='PENDING'
            """, decision.name(), tenantId, requestId);
        if (updated != 1) {
            throw new IllegalStateException("Request state changed during decision");
        }
        jdbc.update("""
            INSERT INTO access_decisions
              (tenant_id,request_id,requester_id,approver_id,decision)
            VALUES (?,?,?,?,?)
            """, tenantId, requestId, request.requesterId(), approverId, decision.name());
    }

    private record RequestState(UUID requesterId, String status) {}
}
