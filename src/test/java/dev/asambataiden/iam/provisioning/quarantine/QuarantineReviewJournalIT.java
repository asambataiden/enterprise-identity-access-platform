package dev.asambataiden.iam.provisioning.quarantine;

import dev.asambataiden.iam.provisioning.execution.ProvisioningLeaseService;
import dev.asambataiden.iam.provisioning.execution.ProvisioningAttemptJournal;
import java.util.UUID;
import java.util.concurrent.*;

import dev.asambataiden.iam.accessrequest.ApprovalDecisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class QuarantineReviewJournalIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("iamdb").withUsername("iam_app").withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ProvisioningLeaseService leases;
    @Autowired ProvisioningAttemptJournal journal;
    @Autowired QuarantineReviewJournal reviews;
    @Autowired ApprovalDecisionService decisions;
    UUID tenant, requester, approver, subject, application, requestId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM provisioning_quarantine_reviews");
        jdbc.update("DELETE FROM provisioning_mock_observations");
        jdbc.update("DELETE FROM provisioning_attempts");
        jdbc.update("DELETE FROM provisioning_work_items");
        jdbc.update("DELETE FROM access_decisions");
        jdbc.update("DELETE FROM access_requests");
        jdbc.update("DELETE FROM employee_permissions");
        jdbc.update("DELETE FROM employee_identities");
        jdbc.update("DELETE FROM application_roles");
        jdbc.update("DELETE FROM applications");
        jdbc.update("DELETE FROM employees");
        jdbc.update("DELETE FROM tenants");
        tenant = UUID.randomUUID();
        requester = UUID.randomUUID();
        approver = UUID.randomUUID();
        subject = UUID.randomUUID();
        application = UUID.randomUUID();
        requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "lease-tenant-" + tenant);
        for (UUID person : new UUID[]{requester, approver, subject}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",
                    tenant, person);
        }
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')",
                tenant, approver);
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",
                tenant, application);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')",
                tenant, application);
        jdbc.update("""
            INSERT INTO access_requests
                (id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER','lease-seed')
            """, requestId, tenant, requester, subject, application);
    }

    private void approve() {
        decisions.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED);
    }

    private void quarantine() {
        approve();
        for (int attempt = 1; attempt <= 3; attempt++) {
            var lease = leases.claim(tenant, 30).orElseThrow();
            assertTrue(leases.fail(lease, ProvisioningLeaseService.FailureCode.TIMEOUT, 1));
            if (attempt < 3) {
                jdbc.update("""
                    UPDATE provisioning_work_items
                       SET next_attempt_at=CURRENT_TIMESTAMP - interval '1 second'
                     WHERE tenant_id=? AND request_id=?
                    """, tenant, requestId);
            }
        }
    }

    @Test
    void quarantineReviewIsInformationalAppendOnlyAndReplaySafe() {
        quarantine();
        UUID reviewId = UUID.randomUUID();
        var review = new QuarantineReviewJournal.Review(
            tenant, requestId, reviewId, approver,
            QuarantineReviewJournal.Decision.RECONCILE_REQUIRED,
            QuarantineReviewJournal.ReasonCode.UNCERTAIN_TARGET_STATE);
        assertTrue(reviews.record(review));
        assertFalse(reviews.record(review));
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_quarantine_reviews
             WHERE tenant_id=? AND request_id=?
            """, Long.class, tenant, requestId));
        assertEquals("QUARANTINED", jdbc.queryForObject("""
            SELECT state FROM provisioning_work_items
             WHERE tenant_id=? AND request_id=?
            """, String.class, tenant, requestId));
        assertEquals(3, jdbc.queryForObject("""
            SELECT attempt_count FROM provisioning_work_items
             WHERE tenant_id=? AND request_id=?
            """, Integer.class, tenant, requestId));
        assertTrue(leases.claim(tenant, 30).isEmpty());
        var changed = new QuarantineReviewJournal.Review(
            tenant, requestId, reviewId, approver,
            QuarantineReviewJournal.Decision.HOLD,
            QuarantineReviewJournal.ReasonCode.POLICY_REVIEW);
        assertThrows(IllegalStateException.class, () -> reviews.record(changed));
    }

    @Test
    void nonQuarantinedWrongTenantAndInactiveReviewerCannotCreateEvidence() {
        var early = new QuarantineReviewJournal.Review(
            tenant, requestId, UUID.randomUUID(), approver,
            QuarantineReviewJournal.Decision.HOLD,
            QuarantineReviewJournal.ReasonCode.POLICY_REVIEW);
        assertFalse(reviews.record(early));
        quarantine();
        assertFalse(reviews.record(new QuarantineReviewJournal.Review(
            UUID.randomUUID(), requestId, UUID.randomUUID(), approver,
            QuarantineReviewJournal.Decision.HOLD,
            QuarantineReviewJournal.ReasonCode.POLICY_REVIEW)));
        assertFalse(reviews.record(new QuarantineReviewJournal.Review(
            tenant, requestId, UUID.randomUUID(), UUID.randomUUID(),
            QuarantineReviewJournal.Decision.HOLD,
            QuarantineReviewJournal.ReasonCode.POLICY_REVIEW)));
        jdbc.update("""
            UPDATE employees SET lifecycle_status='DISABLED'
            WHERE tenant_id=? AND id=?
            """, tenant, approver);
        assertFalse(reviews.record(early));
        assertEquals(0L, jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_quarantine_reviews
             WHERE tenant_id=? AND request_id=?
            """, Long.class, tenant, requestId));
    }
}
