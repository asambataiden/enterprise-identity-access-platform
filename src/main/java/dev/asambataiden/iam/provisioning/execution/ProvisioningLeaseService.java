package dev.asambataiden.iam.provisioning.execution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal, tenant-scoped durable-work coordination only.
 * This class NEVER calls a target system or grants an entitlement.
 */
@Service
public class ProvisioningLeaseService {
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;

    public ProvisioningLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Lease(UUID tenantId, UUID requestId, UUID token, int attempt) {}

    public enum FailureCode {
        TEMPORARY_DEPENDENCY_FAILURE, TIMEOUT, UNKNOWN
    }

    /**
     * Claim the oldest eligible work item, or recover an expired lease.
     * Locks only a single row, without blocking other worker claims.
     * Lease tokens are freshly generated and fence delayed workers.
     */
    @Transactional
    public Optional<Lease> claim(UUID tenantId, int leaseSeconds) {
        if (tenantId == null || leaseSeconds < 5 || leaseSeconds > 300) {
            throw new IllegalArgumentException("Invalid tenant or lease duration");
        }
        // A final-attempt crash cannot leave a permanently expired LEASED row.
        jdbc.update("""
            UPDATE provisioning_work_items
               SET state='QUARANTINED', lease_token=NULL, lease_expires_at=NULL,
                   last_error_code='UNKNOWN'
             WHERE tenant_id=? AND state='LEASED' AND attempt_count >= ?
               AND lease_expires_at <= CURRENT_TIMESTAMP
            """, tenantId, MAX_ATTEMPTS);
        List<UUID> ids = jdbc.query("""
            SELECT request_id FROM provisioning_work_items
             WHERE tenant_id=?
               AND attempt_count < ?
               AND ((state IN ('READY','RETRY_WAIT') AND next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (state='LEASED' AND lease_expires_at <= CURRENT_TIMESTAMP))
             ORDER BY created_at, request_id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, (rs, row) -> rs.getObject("request_id", UUID.class), tenantId, MAX_ATTEMPTS);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        UUID requestId = ids.getFirst();
        UUID token = UUID.randomUUID();
        int changed = jdbc.update("""
            UPDATE provisioning_work_items
               SET state='LEASED', lease_token=?,
                   lease_expires_at=CURRENT_TIMESTAMP + make_interval(secs => ?),
                   attempt_count=attempt_count + 1,
                   last_error_code=NULL
             WHERE tenant_id=? AND request_id=?
            """, token, leaseSeconds, tenantId, requestId);
        if (changed != 1) {
            throw new IllegalStateException("Claimed work item disappeared");
        }
        Integer attempts = jdbc.queryForObject("""
            SELECT attempt_count FROM provisioning_work_items
             WHERE tenant_id=? AND request_id=?
            """, Integer.class, tenantId, requestId);
        if (attempts == null || attempts < 1 || attempts > MAX_ATTEMPTS) {
            throw new IllegalStateException("Invalid attempt count for claimed work item");
        }
        return Optional.of(new Lease(tenantId, requestId, token, attempts));
    }

    /**
     * Extend an unexpired lease only for its current token holder.
     * A stale worker may never renew another worker's recovered lease.
     */
    @Transactional
    public boolean renew(Lease lease, int leaseSeconds) {
        requireLease(lease);
        if (leaseSeconds < 5 || leaseSeconds > 300) {
            throw new IllegalArgumentException("Invalid lease duration");
        }
        return jdbc.update("""
            UPDATE provisioning_work_items
               SET lease_expires_at=CURRENT_TIMESTAMP + make_interval(secs => ?)
             WHERE tenant_id=? AND request_id=? AND state='LEASED'
               AND lease_token=? AND lease_expires_at > CURRENT_TIMESTAMP
            """, leaseSeconds, lease.tenantId(), lease.requestId(), lease.token()) == 1;
    }

    /**
     * Release the current lease to bounded backoff or quarantine.
     * No exception messages or secrets are persisted: only allowlisted codes.
     */
    @Transactional
    public boolean fail(Lease lease, FailureCode error, int retryDelaySeconds) {
        requireLease(lease);
        if (error == null || retryDelaySeconds < 1 || retryDelaySeconds > 3600) {
            throw new IllegalArgumentException("Invalid failure code or retry delay");
        }
        return jdbc.update("""
            UPDATE provisioning_work_items
               SET state=CASE WHEN attempt_count >= ? THEN 'QUARANTINED'
                              ELSE 'RETRY_WAIT' END,
                   lease_token=NULL, lease_expires_at=NULL,
                   next_attempt_at=CURRENT_TIMESTAMP + make_interval(secs => ?),
                   last_error_code=?
             WHERE tenant_id=? AND request_id=? AND state='LEASED'
               AND lease_token=? AND lease_expires_at > CURRENT_TIMESTAMP
            """, MAX_ATTEMPTS, retryDelaySeconds, error.name(),
                lease.tenantId(), lease.requestId(), lease.token()) == 1;
    }

    private static void requireLease(Lease lease) {
        if (lease == null || lease.tenantId() == null ||
                lease.requestId() == null || lease.token() == null ||
                lease.attempt() < 1) {
            throw new IllegalArgumentException("Invalid lease");
        }
    }
}
