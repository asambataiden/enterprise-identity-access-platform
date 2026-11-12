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
class SimulatedProvisioningServiceIT {
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
    @Autowired SimulatedProvisioningService simulation;
    @Autowired ApprovalDecisionService decisions;
    UUID tenant, requester, approver, subject, application, requestId;

    @BeforeEach
    void seed() {
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
    void approvedClaimCreatesOneReadbackVerifiedSyntheticEntitlement() {
        approve();
        var lease = leases.claim(tenant, 30).orElseThrow();
        assertTrue(simulation.executeAndVerify(lease));
        assertEquals("SUCCEEDED", state());
        assertEquals(1L, countSynthetic());
        assertTrue(leases.claim(tenant, 30).isEmpty());
        assertFalse(simulation.executeAndVerify(lease));
        assertEquals(1L, countSynthetic());
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM simulated_entitlements s
             JOIN access_requests ar ON ar.tenant_id=s.tenant_id AND ar.id=s.request_id
            WHERE s.tenant_id=? AND s.request_id=? AND s.employee_id=ar.employee_id
              AND s.application_id=ar.application_id AND s.role_key=ar.role_key
              AND s.originating_lease_token=?
            """, Long.class, tenant, requestId, lease.token()));
    }

    @Test
    void rejectionNeverCreatesSyntheticEntitlement() {
        decisions.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.REJECTED);
        assertTrue(leases.claim(tenant, 30).isEmpty());
        assertEquals(0L, countSynthetic());
    }

    @Test
    void wrongTenantAndStaleTokenCannotExecute() {
        approve();
        var first = leases.claim(tenant, 30).orElseThrow();
        assertFalse(simulation.executeAndVerify(new ProvisioningLeaseService.Lease(
                UUID.randomUUID(), requestId, first.token(), first.attempt())));
        assertFalse(simulation.executeAndVerify(new ProvisioningLeaseService.Lease(
                tenant, requestId, UUID.randomUUID(), first.attempt())));
        jdbc.update("""
            UPDATE provisioning_work_items SET lease_expires_at=CURRENT_TIMESTAMP - interval '1 second'
            WHERE tenant_id=? AND request_id=?
            """, tenant, requestId);
        assertFalse(simulation.executeAndVerify(first));
        var recovered = leases.claim(tenant, 30).orElseThrow();
        assertFalse(simulation.executeAndVerify(first));
        assertTrue(simulation.executeAndVerify(recovered));
        assertEquals(1L, countSynthetic());
        assertEquals("SUCCEEDED", state());
    }

    @Test
    void directlyForcingSuccessWithoutSyntheticVerificationIsRejected() {
        approve();
        var lease = leases.claim(tenant, 30).orElseThrow();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                    UPDATE provisioning_work_items SET state='SUCCEEDED',
                    lease_token=NULL, lease_expires_at=NULL, completed_at=clock_timestamp()
                    WHERE tenant_id=? AND request_id=?
                    """, tenant, requestId));
        assertEquals("LEASED", state());
        assertTrue(simulation.executeAndVerify(lease));
    }

    @Test
    void disabledSubjectCannotBeSimulatedAndTransactionRollsBack() {
        approve();
        var lease = leases.claim(tenant, 30).orElseThrow();
        jdbc.update("""
            UPDATE employees SET lifecycle_status='DISABLED'
            WHERE tenant_id=? AND id=?
            """, tenant, subject);
        assertThrows(IllegalStateException.class, () -> simulation.executeAndVerify(lease));
        assertEquals(0L, countSynthetic());
        assertEquals("LEASED", state());
    }

    @Test
    void concurrentExecutionOfSameLeaseHasOneWinner() throws Exception {
        approve();
        var lease = leases.claim(tenant, 30).orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Future<Boolean> a = pool.submit(() -> { gate.await(); return simulation.executeAndVerify(lease); });
            Future<Boolean> b = pool.submit(() -> { gate.await(); return simulation.executeAndVerify(lease); });
            gate.countDown();
            assertEquals(1, (a.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(15, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1L, countSynthetic());
            assertEquals("SUCCEEDED", state());
        } finally {
            pool.shutdownNow();
        }
    }

    private long countSynthetic() {
        return jdbc.queryForObject("""
            SELECT count(*) FROM simulated_entitlements
            WHERE tenant_id=? AND request_id=?
            """, Long.class, tenant, requestId);
    }

    private String state() {
        return jdbc.queryForObject("""
            SELECT state FROM provisioning_work_items
            WHERE tenant_id=? AND request_id=?
            """, String.class, tenant, requestId);
    }
}
