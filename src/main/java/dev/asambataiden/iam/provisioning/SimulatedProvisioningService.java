package dev.asambataiden.iam.provisioning;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synthetic PostgreSQL-only adapter: never calls Keycloak or any external target.
 * "SUCCEEDED" means the synthetic entitlement row was read back and validated.
 */
@Service
public class SimulatedProvisioningService {
    private final JdbcTemplate jdbc;

    public SimulatedProvisioningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record WorkState(String state, UUID token, int attempt, boolean unexpired) {}

    /** Returns false without side effects when the lease is missing, stale or expired. */
    @Transactional
    public boolean executeAndVerify(ProvisioningLeaseService.Lease lease) {
        if (lease == null || lease.tenantId() == null || lease.requestId() == null
                || lease.token() == null || lease.attempt() < 1) {
            throw new IllegalArgumentException("Invalid simulation lease");
        }

        // Hold the same row lock used by claim/recovery to fence delayed workers.
        List<WorkState> states = jdbc.query("""
            SELECT state, lease_token, attempt_count,
                   lease_expires_at > clock_timestamp() AS unexpired
              FROM provisioning_work_items
             WHERE tenant_id=? AND request_id=?
             FOR UPDATE
            """, (rs, i) -> new WorkState(
                rs.getString("state"),
                rs.getObject("lease_token", UUID.class),
                rs.getInt("attempt_count"),
                rs.getBoolean("unexpired")), lease.tenantId(), lease.requestId());
        if (states.size() != 1) {
            return false;
        }
        WorkState state = states.getFirst();
        if (!"LEASED".equals(state.state()) || !lease.token().equals(state.token())
                || state.attempt() != lease.attempt() || !state.unexpired()) {
            return false;
        }

        // A real adapter must not be substituted here. The write goes ONLY to
        // the explicitly named synthetic verification table.
        int inserted = jdbc.update("""
            INSERT INTO simulated_entitlements
                (tenant_id,request_id,employee_id,application_id,role_key,originating_lease_token)
            SELECT ar.tenant_id,ar.id,ar.employee_id,ar.application_id,ar.role_key,?
              FROM access_requests ar
              JOIN employees e ON e.tenant_id=ar.tenant_id AND e.id=ar.employee_id
              JOIN applications a ON a.tenant_id=ar.tenant_id AND a.id=ar.application_id
             WHERE ar.tenant_id=? AND ar.id=? AND ar.status='APPROVED'
               AND e.lifecycle_status='ACTIVE' AND a.lifecycle_status='ACTIVE'
            ON CONFLICT (tenant_id,request_id) DO NOTHING
            """, lease.token(), lease.tenantId(), lease.requestId());
        if (inserted != 1) {
            // Fail closed; any previous synthetic evidence must be investigated.
            throw new IllegalStateException("Synthetic entitlement not freshly created");
        }

        Integer verified = jdbc.queryForObject("""
            SELECT count(*) FROM simulated_entitlements s
            JOIN access_requests ar ON ar.tenant_id=s.tenant_id AND ar.id=s.request_id
            WHERE s.tenant_id=? AND s.request_id=? AND ar.status='APPROVED'
              AND s.employee_id=ar.employee_id AND s.application_id=ar.application_id
              AND s.role_key=ar.role_key AND s.originating_lease_token=?
            """, Integer.class, lease.tenantId(), lease.requestId(), lease.token());
        if (verified == null || verified != 1) {
            throw new IllegalStateException("Synthetic entitlement verification failed");
        }

        int completed = jdbc.update("""
            UPDATE provisioning_work_items
               SET state='SUCCEEDED', lease_token=NULL, lease_expires_at=NULL,
                   completed_at=clock_timestamp(), last_error_code=NULL
             WHERE tenant_id=? AND request_id=? AND state='LEASED'
               AND lease_token=? AND attempt_count=?
               AND lease_expires_at > clock_timestamp()
            """, lease.tenantId(), lease.requestId(), lease.token(), lease.attempt());
        if (completed != 1) {
            throw new IllegalStateException("Lease expired during synthetic completion");
        }
        return true;
    }
}
