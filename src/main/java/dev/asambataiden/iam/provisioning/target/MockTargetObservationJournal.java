package dev.asambataiden.iam.provisioning.target;

import dev.asambataiden.iam.provisioning.execution.ProvisioningLeaseService;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock observation writer. This service does NOT mark provisioning SUCCEEDED.
 * The conditional row lock fences expired or recovered leases.
 */
@Service
public class MockTargetObservationJournal {
    public enum Outcome { VERIFIED, ABSENT, UNKNOWN }

    private final JdbcTemplate jdbc;

    public MockTargetObservationJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean record(ProvisioningLeaseService.Lease lease, Outcome outcome) {
        if (lease == null || lease.tenantId() == null || lease.requestId() == null
                || lease.token() == null || lease.attempt() < 1 || lease.attempt() > 3
                || outcome == null) {
            throw new IllegalArgumentException("Invalid mock observation");
        }
        List<UUID> current = jdbc.query("""
            SELECT lease_token FROM provisioning_work_items
             WHERE tenant_id=? AND request_id=? AND state='LEASED'
               AND lease_token=? AND attempt_count=?
               AND lease_expires_at > clock_timestamp()
             FOR UPDATE
            """, (rs, i) -> rs.getObject(1, UUID.class),
                lease.tenantId(), lease.requestId(), lease.token(), lease.attempt());
        if (current.size() != 1) {
            return false;
        }
        Integer started = jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_attempts
             WHERE tenant_id=? AND request_id=? AND attempt_number=?
               AND lease_token=? AND outcome='STARTED'
            """, Integer.class, lease.tenantId(), lease.requestId(),
                lease.attempt(), lease.token());
        if (started == null || started != 1) {
            return false;
        }
        int inserted = jdbc.update("""
            INSERT INTO provisioning_mock_observations
                (tenant_id,request_id,attempt_number,lease_token,outcome)
            VALUES (?,?,?,?,?)
            ON CONFLICT (tenant_id,request_id,attempt_number) DO NOTHING
            """, lease.tenantId(), lease.requestId(), lease.attempt(),
                lease.token(), outcome.name());
        if (inserted == 1) {
            return true;
        }
        Integer identical = jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_mock_observations
             WHERE tenant_id=? AND request_id=? AND attempt_number=?
               AND lease_token=? AND outcome=?
            """, Integer.class, lease.tenantId(), lease.requestId(),
                lease.attempt(), lease.token(), outcome.name());
        if (identical == null || identical != 1) {
            throw new IllegalStateException("Conflicting mock observation on same attempt");
        }
        return false;
    }
}
