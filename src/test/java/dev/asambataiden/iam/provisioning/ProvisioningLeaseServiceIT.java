package dev.asambataiden.iam.provisioning;

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
class ProvisioningLeaseServiceIT {
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
    @Autowired ApprovalDecisionService decisions;
    UUID tenant, requester, approver, subject, application, requestId;

    @BeforeEach
    void seed() {
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

    @Test
    void journalRequiresApprovedCurrentLeaseAndIsReplaySafe() {
        assertTrue(leases.claim(tenant, 30).isEmpty());
        approve();
        var lease = leases.claim(tenant, 30).orElseThrow();
        assertTrue(journal.start(lease));
        assertFalse(journal.start(lease));
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_attempts
             WHERE tenant_id=? AND request_id=? AND attempt_number=1
               AND outcome='STARTED' AND lease_token=?
            """, Long.class, tenant, requestId, lease.token()));
        assertFalse(journal.start(new ProvisioningLeaseService.Lease(
                tenant, requestId, UUID.randomUUID(), 1)));
    }

    @Test
    void journalUsesStableIdempotencyAcrossRecoveredLeases() {
        approve();
        var old = leases.claim(tenant, 30).orElseThrow();
        assertTrue(journal.start(old));
        String key = ProvisioningAttemptJournal.idempotencyKey(tenant, requestId);
        assertEquals(key, jdbc.queryForObject("""
            SELECT idempotency_key FROM provisioning_attempts
            WHERE tenant_id=? AND request_id=? AND attempt_number=1
            """, String.class, tenant, requestId));
        jdbc.update("""
            UPDATE provisioning_work_items
               SET lease_expires_at=CURRENT_TIMESTAMP - interval '1 second'
             WHERE tenant_id=? AND request_id=?
            """, tenant, requestId);
        var replacement = leases.claim(tenant, 30).orElseThrow();
        assertFalse(journal.start(old));
        assertTrue(journal.start(replacement));
        assertEquals(key, jdbc.queryForObject("""
            SELECT idempotency_key FROM provisioning_attempts
            WHERE tenant_id=? AND request_id=? AND attempt_number=2
            """, String.class, tenant, requestId));
        assertNotEquals(old.token(), replacement.token());
        assertEquals(2L, jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_attempts WHERE tenant_id=? AND request_id=?
            """, Long.class, tenant, requestId));
    }

    @Test
    void journalRejectsForgedAttemptAndWrongTenant() {
        approve();
        var lease = leases.claim(tenant, 30).orElseThrow();
        assertFalse(journal.start(new ProvisioningLeaseService.Lease(
                UUID.randomUUID(), requestId, lease.token(), lease.attempt())));
        assertFalse(journal.start(new ProvisioningLeaseService.Lease(
                tenant, requestId, lease.token(), 2)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                    INSERT INTO provisioning_attempts
                        (tenant_id,request_id,attempt_number,lease_token,idempotency_key)
                    VALUES (?,?,?,?,?)
                    """, UUID.randomUUID(), requestId, 1, UUID.randomUUID(), "forged"));
        assertEquals(0L, jdbc.queryForObject("""
            SELECT count(*) FROM provisioning_attempts WHERE tenant_id=? AND request_id=?
            """, Long.class, tenant, requestId));
    }

    @Test
    void cannotClaimUnapprovedWork() {
        assertTrue(leases.claim(tenant, 30).isEmpty());
        decisions.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.REJECTED);
        assertTrue(leases.claim(tenant, 30).isEmpty());
    }

    @Test
    void oneClaimOnlyAndCurrentTokenRenews() {
        approve();
        var claimed = leases.claim(tenant, 30).orElseThrow();
        assertEquals(requestId, claimed.requestId());
        assertEquals(1, claimed.attempt());
        assertTrue(leases.claim(tenant, 30).isEmpty());
        assertTrue(leases.renew(claimed, 30));
        assertFalse(leases.renew(new ProvisioningLeaseService.Lease(
                tenant, requestId, UUID.randomUUID(), claimed.attempt()), 30));
    }

    @Test
    void expiredLeaseCanBeRecoveredButOldWorkerIsFenced() {
        approve();
        var old = leases.claim(tenant, 30).orElseThrow();
        jdbc.update("""
            UPDATE provisioning_work_items SET lease_expires_at=CURRENT_TIMESTAMP - interval '1 second'
             WHERE tenant_id=? AND request_id=?
            """, tenant, requestId);
        var recovered = leases.claim(tenant, 30).orElseThrow();
        assertEquals(2, recovered.attempt());
        assertNotEquals(old.token(), recovered.token());
        assertFalse(leases.renew(old, 30));
        assertFalse(leases.fail(old, ProvisioningLeaseService.FailureCode.TIMEOUT, 2));
        assertTrue(leases.fail(recovered, ProvisioningLeaseService.FailureCode.TIMEOUT, 2));
        assertEquals("RETRY_WAIT", state());
    }

    @Test
    void failedAttemptsBackOffAndEventuallyQuarantine() {
        approve();
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claimed = leases.claim(tenant, 30).orElseThrow();
            assertEquals(attempt, claimed.attempt());
            assertTrue(leases.fail(claimed, ProvisioningLeaseService.FailureCode.TEMPORARY_DEPENDENCY_FAILURE, 15));
            assertEquals(attempt == 3 ? "QUARANTINED" : "RETRY_WAIT", state());
            assertTrue(leases.claim(tenant, 30).isEmpty());
            if (attempt < 3) {
                jdbc.update("""
                    UPDATE provisioning_work_items SET next_attempt_at=CURRENT_TIMESTAMP - interval '1 second'
                     WHERE tenant_id=? AND request_id=?
                    """, tenant, requestId);
            }
        }
        assertEquals(3, jdbc.queryForObject("""
            SELECT attempt_count FROM provisioning_work_items WHERE tenant_id=? AND request_id=?
            """, Integer.class, tenant, requestId));
        assertTrue(leases.claim(tenant, 30).isEmpty());
    }

    @Test
    void finalAttemptCrashQuarantinesExpiredLease() {
        approve();
        for (int attempt = 1; attempt < 3; attempt++) {
            var lease = leases.claim(tenant, 30).orElseThrow();
            assertTrue(leases.fail(lease, ProvisioningLeaseService.FailureCode.TIMEOUT, 1));
            jdbc.update("""
                UPDATE provisioning_work_items SET next_attempt_at=CURRENT_TIMESTAMP - interval '1 second'
                 WHERE tenant_id=? AND request_id=?
                """, tenant, requestId);
        }
        var last = leases.claim(tenant, 30).orElseThrow();
        assertEquals(3, last.attempt());
        jdbc.update("""
            UPDATE provisioning_work_items SET lease_expires_at=CURRENT_TIMESTAMP - interval '1 second'
             WHERE tenant_id=? AND request_id=?
            """, tenant, requestId);
        assertTrue(leases.claim(tenant, 30).isEmpty());
        assertEquals("QUARANTINED", state());
        assertFalse(leases.renew(last, 30));
    }

    @Test
    void tenantIsolationAndInputValidation() {
        approve();
        assertTrue(leases.claim(UUID.randomUUID(), 30).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> leases.claim(null, 30));
        assertThrows(IllegalArgumentException.class, () -> leases.claim(tenant, 0));
        assertEquals(requestId, leases.claim(tenant, 30).orElseThrow().requestId());
        assertThrows(IllegalArgumentException.class,
                () -> leases.fail(null, ProvisioningLeaseService.FailureCode.UNKNOWN, 2));
    }

    @Test
    void concurrentWorkersCannotClaimSameUnexpiredItem() throws Exception {
        approve();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Future<Boolean> a = pool.submit(() -> { gate.await(); return leases.claim(tenant, 30).isPresent(); });
            Future<Boolean> b = pool.submit(() -> { gate.await(); return leases.claim(tenant, 30).isPresent(); });
            gate.countDown();
            assertEquals(1, (a.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(15, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals("LEASED", state());
        } finally {
            pool.shutdownNow();
        }
    }

    private String state() {
        return jdbc.queryForObject("""
            SELECT state FROM provisioning_work_items WHERE tenant_id=? AND request_id=?
            """, String.class, tenant, requestId);
    }
}
