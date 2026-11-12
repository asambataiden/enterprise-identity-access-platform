package dev.asambataiden.iam.provisioning;

import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicitly invoked mock-only orchestration; NOT a Spring bean or scheduler.
 * Journal transaction commits before any adapter call.
 * The result is mock readback evidence, not production provisioning success.
 */
public final class MockProvisioningOrchestrator {
    private final ProvisioningAttemptJournal starts;
    private final MockTargetObservationJournal observations;
    private final JdbcTemplate jdbc;
    private final ProvisioningTargetAdapter target;

    public MockProvisioningOrchestrator(ProvisioningAttemptJournal starts,
            MockTargetObservationJournal observations, JdbcTemplate jdbc,
            LoopbackHttpProvisioningTargetAdapter target) {
        this.starts = Objects.requireNonNull(starts);
        this.observations = Objects.requireNonNull(observations);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.target = Objects.requireNonNull(target);
    }

    public MockTargetObservationJournal.Outcome execute(ProvisioningLeaseService.Lease lease) {
        // A newly inserted record is committed by the @Transactional journal
        // before network I/O. Exact same-attempt replay is intentionally blocked.
        if (!starts.start(lease)) {
            throw new IllegalStateException("No newly committed, valid attempt");
        }
        List<ProvisioningTargetAdapter.Entitlement> identities = jdbc.query("""
            SELECT ar.tenant_id, ar.id, ar.employee_id, ar.application_id, ar.role_key
              FROM access_requests ar
              JOIN employees e ON e.tenant_id=ar.tenant_id AND e.id=ar.employee_id
              JOIN applications a ON a.tenant_id=ar.tenant_id AND a.id=ar.application_id
             WHERE ar.tenant_id=? AND ar.id=? AND ar.status='APPROVED'
               AND e.lifecycle_status='ACTIVE' AND a.lifecycle_status='ACTIVE'
            """, (rs, i) -> new ProvisioningTargetAdapter.Entitlement(
                rs.getObject("tenant_id", java.util.UUID.class),
                rs.getObject("id", java.util.UUID.class),
                rs.getObject("employee_id", java.util.UUID.class),
                rs.getObject("application_id", java.util.UUID.class),
                rs.getString("role_key")), lease.tenantId(), lease.requestId());
        if (identities.size() != 1) {
            // The start evidence remains; no external operation is attempted.
            throw new IllegalStateException("Approved active entitlement not found");
        }
        var identity = identities.getFirst();
        String key = ProvisioningAttemptJournal.idempotencyKey(lease.tenantId(), lease.requestId());
        MockTargetObservationJournal.Outcome outcome;
        try {
            // Reconciliation first: the prior attempt might have applied the
            // entitlement before its acknowledgement or observation was lost.
            if (target.isEffective(identity)) {
                outcome = MockTargetObservationJournal.Outcome.VERIFIED;
            } else {
                try {
                    target.apply(identity, key);
                } catch (IllegalStateException uncertain) {
                    // An acknowledgement may be lost after a successful POST.
                    // Re-read the mock target before classifying the attempt.
                }
                outcome = target.isEffective(identity)
                        ? MockTargetObservationJournal.Outcome.VERIFIED
                        : MockTargetObservationJournal.Outcome.ABSENT;
            }
        } catch (IllegalStateException uncertainReadback) {
            // Never issue a speculative POST if initial readback is uncertain.
            outcome = MockTargetObservationJournal.Outcome.UNKNOWN;
        }
        if (!observations.record(lease, outcome)) {
            throw new IllegalStateException("Lease lost before mock readback could be recorded");
        }
        return outcome;
    }
}
