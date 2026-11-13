package dev.asambataiden.iam.provisioning.quarantine;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal evidence recorder, NOT an authorized administrative API.
 * Records informational review facts only; never releases or retries work.
 * Future endpoints must authenticate the reviewer and authorize tenant scope.
 */
@Service
public class QuarantineReviewJournal {
    public enum Decision { HOLD, ESCALATE, RECONCILE_REQUIRED }
    public enum ReasonCode {
        UNCERTAIN_TARGET_STATE, POSSIBLE_DUPLICATE,
        POLICY_REVIEW, DEPENDENCY_UNAVAILABLE
    }

    private final JdbcTemplate jdbc;

    public QuarantineReviewJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Review(UUID tenantId, UUID requestId, UUID reviewId,
                         UUID reviewerEmployeeId, Decision decision, ReasonCode reasonCode) {}

    /**
     * Append a diagnostic review of currently quarantined work.
     * A supplied reviewer ID is evidence metadata, not verified authentication.
     * @return true if first insertion, false for an identical replay.
     */
    @Transactional
    public boolean record(Review review) {
        if (review == null || review.tenantId() == null || review.requestId() == null ||
                review.reviewId() == null || review.reviewerEmployeeId() == null ||
                review.decision() == null || review.reasonCode() == null) {
            throw new IllegalArgumentException("Complete quarantine review identity required");
        }
        List<String> state = jdbc.query("""
            SELECT state FROM provisioning_work_items
             WHERE tenant_id=? AND request_id=? FOR UPDATE
            """, (rs, i) -> rs.getString(1), review.tenantId(), review.requestId());
        if (state.size() != 1 || !"QUARANTINED".equals(state.getFirst())) {
            return false;
        }
        Integer active = jdbc.queryForObject("""
            SELECT count(*) FROM employees WHERE tenant_id=? AND id=?
              AND lifecycle_status='ACTIVE'
            """, Integer.class, review.tenantId(), review.reviewerEmployeeId());
        if (active == null || active != 1) {
            return false;
        }
        int inserted = jdbc.update("""
            INSERT INTO provisioning_quarantine_reviews
                (tenant_id, request_id, review_id, reviewer_employee_id, decision, reason_code)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (tenant_id,request_id,review_id) DO NOTHING
            """, review.tenantId(), review.requestId(), review.reviewId(),
                review.reviewerEmployeeId(), review.decision().name(),
                review.reasonCode().name());
        if (inserted == 1) {
            return true;
        }
        Integer matches = jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_quarantine_reviews
             WHERE tenant_id=? AND request_id=? AND review_id=?
               AND reviewer_employee_id=? AND decision=? AND reason_code=?
            """, Integer.class, review.tenantId(), review.requestId(), review.reviewId(),
                review.reviewerEmployeeId(), review.decision().name(),
                review.reasonCode().name());
        if (matches == null || matches != 1) {
            throw new IllegalStateException("Review ID already used with conflicting evidence");
        }
        return false;
    }
}
