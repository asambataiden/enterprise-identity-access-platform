package dev.asambataiden.iam.provisioning;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records durable STARTED evidence before a future adapter invocation.
 * Does not invoke an adapter, change lease state, or mark provisioning successful.
 */
@Service
public class ProvisioningAttemptJournal {
    private final JdbcTemplate jdbc;

    public ProvisioningAttemptJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record CurrentLease(String state, UUID token, int attempt, boolean unexpired) {}

    /**
     * @return true if this invocation created the attempt; false for an exact replay.
     */
    @Transactional
    public boolean start(ProvisioningLeaseService.Lease lease) {
        if (lease == null || lease.tenantId() == null || lease.requestId() == null
                || lease.token() == null || lease.attempt() < 1 || lease.attempt() > 3) {
            throw new IllegalArgumentException("Invalid provisioning lease");
        }
        List<CurrentLease> rows = jdbc.query("""
            SELECT state, lease_token, attempt_count,
                   lease_expires_at > clock_timestamp() AS unexpired
              FROM provisioning_work_items
             WHERE tenant_id=? AND request_id=? FOR UPDATE
            """, (rs, i) -> new CurrentLease(
                rs.getString("state"), rs.getObject("lease_token", UUID.class),
                rs.getInt("attempt_count"), rs.getBoolean("unexpired")),
                lease.tenantId(), lease.requestId());
        if (rows.size() != 1) {
            return false;
        }
        CurrentLease current = rows.getFirst();
        if (!"LEASED".equals(current.state()) || !current.unexpired()
                || !lease.token().equals(current.token())
                || lease.attempt() != current.attempt()) {
            return false;
        }

        String key = idempotencyKey(lease.tenantId(), lease.requestId());
        int inserted = jdbc.update("""
            INSERT INTO provisioning_attempts
                (tenant_id,request_id,attempt_number,lease_token,idempotency_key)
            VALUES (?,?,?,?,?)
            ON CONFLICT (tenant_id,request_id,attempt_number) DO NOTHING
            """, lease.tenantId(), lease.requestId(), lease.attempt(), lease.token(), key);
        if (inserted == 1) {
            return true;
        }
        Integer matches = jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_attempts
             WHERE tenant_id=? AND request_id=? AND attempt_number=?
               AND lease_token=? AND idempotency_key=?
            """, Integer.class, lease.tenantId(), lease.requestId(), lease.attempt(),
                lease.token(), key);
        if (matches == null || matches != 1) {
            throw new IllegalStateException("Attempt evidence conflicts with active lease");
        }
        return false;
    }

    /**
     * Stable across claim retries for one approved request, unlike fencing tokens.
     * The future adapter must also enforce tenant isolation on its own side.
     */
    public static String idempotencyKey(UUID tenantId, UUID requestId) {
        if (tenantId == null || requestId == null) {
            throw new IllegalArgumentException("Missing idempotency identity");
        }
        String input = "iam:provision:v1:" + tenantId + ":" + requestId;
        return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
