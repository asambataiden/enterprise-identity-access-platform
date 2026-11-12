package dev.asambataiden.iam.accessrequest;

import java.util.UUID;
import java.util.concurrent.*;

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
class ApprovalDecisionServiceIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("iamdb").withUsername("iam_app").withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApprovalDecisionService service;
    @Autowired JdbcTemplate jdbc;
    UUID tenant, requester, approver, alternate, employee, application, requestId;

    @BeforeEach
    void seed() {
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
        alternate = UUID.randomUUID();
        employee = UUID.randomUUID();
        application = UUID.randomUUID();
        requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "org-" + tenant);
        for (UUID id : new UUID[]{requester, approver, alternate, employee}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", tenant, id);
        }
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",
                tenant, application);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')",
                tenant, application);
        jdbc.update("""
            INSERT INTO access_requests
            (id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER','decision-key')
            """, requestId, tenant, requester, employee, application);
        grant(approver);
    }

    private void grant(UUID person) {
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')",
                tenant, person);
    }
    private String status() {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id=?", String.class, requestId);
    }
    private long decisions() {
        return jdbc.queryForObject("SELECT count(*) FROM access_decisions WHERE tenant_id=? AND request_id=?",
                Long.class, tenant, requestId);
    }
    private void assertUnchanged() {
        assertEquals("PENDING", status());
        assertEquals(0, decisions());
    }

    @Test
    void authorizedDistinctApproverCanApproveOnce() {
        service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED);
        assertEquals("APPROVED", status());
        assertEquals(1, decisions());
        assertEquals(approver, jdbc.queryForObject(
                "SELECT approver_id FROM access_decisions WHERE request_id=?", UUID.class, requestId));
        assertThrows(IllegalStateException.class,
                () -> service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.REJECTED));
        assertEquals("APPROVED", status());
        assertEquals(1, decisions());
    }

    @Test
    void distinctApproverCanRejectWithoutCreatingGrant() {
        service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.REJECTED);
        assertEquals("REJECTED", status());
        assertEquals(1, decisions());
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT decision FROM access_decisions WHERE request_id=?", String.class, requestId));
    }

    @Test
    void missingOrRevokedPermissionDeniedWithoutMutation() {
        assertThrows(SecurityException.class,
                () -> service.decide(tenant, alternate, requestId, ApprovalDecisionService.Decision.APPROVED));
        assertUnchanged();
        jdbc.update("DELETE FROM employee_permissions WHERE tenant_id=? AND employee_id=?", tenant, approver);
        assertThrows(SecurityException.class,
                () -> service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED));
        assertUnchanged();
    }

    @Test
    void requesterCannotSelfApproveEvenWithGrant() {
        grant(requester);
        assertThrows(SecurityException.class,
                () -> service.decide(tenant, requester, requestId, ApprovalDecisionService.Decision.APPROVED));
        assertUnchanged();
    }

    @Test
    void disabledApproverOrSubjectFailsClosed() {
        jdbc.update("UPDATE employees SET lifecycle_status='DISABLED' WHERE tenant_id=? AND id=?", tenant, approver);
        assertThrows(SecurityException.class,
                () -> service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED));
        assertUnchanged();
        jdbc.update("UPDATE employees SET lifecycle_status='ACTIVE' WHERE tenant_id=? AND id=?", tenant, approver);
        jdbc.update("UPDATE employees SET lifecycle_status='DISABLED' WHERE tenant_id=? AND id=?", tenant, employee);
        assertThrows(IllegalArgumentException.class,
                () -> service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED));
        assertUnchanged();
    }

    @Test
    void foreignTenantAndUnknownRequestFailWithoutDisclosureOrWrite() {
        UUID foreign = UUID.randomUUID();
        UUID foreignApprover = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", foreign, "foreign-" + foreign);
        jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", foreign, foreignApprover);
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')",
                foreign, foreignApprover);
        assertThrows(IllegalArgumentException.class,
                () -> service.decide(foreign, foreignApprover, requestId, ApprovalDecisionService.Decision.APPROVED));
        assertThrows(IllegalArgumentException.class,
                () -> service.decide(tenant, approver, UUID.randomUUID(), ApprovalDecisionService.Decision.APPROVED));
        assertUnchanged();
    }

    @Test
    void concurrentOpposingDecisionsAllowOnlyOneWinner() throws Exception {
        grant(alternate);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(() -> tryDecision(gate, approver, ApprovalDecisionService.Decision.APPROVED));
            Future<Boolean> second = pool.submit(() -> tryDecision(gate, alternate, ApprovalDecisionService.Decision.REJECTED));
            gate.countDown();
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) +
                    (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, decisions());
            assertTrue(status().equals("APPROVED") || status().equals("REJECTED"));
            assertEquals(status(), jdbc.queryForObject(
                    "SELECT decision FROM access_decisions WHERE request_id=?", String.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void approvalPersistsMatchingAuditEventAtomically() {
        service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED);
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM approval_audit_events a JOIN access_decisions d
              ON a.tenant_id=d.tenant_id AND a.request_id=d.request_id
             WHERE a.tenant_id=? AND a.request_id=?
               AND a.approver_id=d.approver_id AND a.requester_id=d.requester_id
               AND a.outcome=d.decision AND a.occurred_at=d.decided_at
               AND a.event_type='ACCESS_REQUEST_DECIDED'
            """, Long.class, tenant, requestId));
        assertThrows(IllegalStateException.class,
                () -> service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.REJECTED));
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM approval_audit_events WHERE tenant_id=? AND request_id=?
            """, Long.class, tenant, requestId));
    }

    @Test
    void deniedApprovalCreatesNoAuditEvidence() {
        assertThrows(SecurityException.class,
                () -> service.decide(tenant, requester, requestId, ApprovalDecisionService.Decision.REJECTED));
        assertEquals(0L, jdbc.queryForObject("""
            SELECT count(*) FROM approval_audit_events WHERE tenant_id=? AND request_id=?
            """, Long.class, tenant, requestId));
    }

    @Test
    void auditEvidenceRejectsUpdateAndDelete() {
        service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.REJECTED);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
            UPDATE approval_audit_events SET outcome='APPROVED' WHERE tenant_id=? AND request_id=?
            """, tenant, requestId));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
            DELETE FROM approval_audit_events WHERE tenant_id=? AND request_id=?
            """, tenant, requestId));
        assertEquals("REJECTED", jdbc.queryForObject("""
            SELECT outcome FROM approval_audit_events WHERE tenant_id=? AND request_id=?
            """, String.class, tenant, requestId));
    }

    @Test
    void auditInsertionFailureRollsBackRequestAndDecision() {
        // Emulate an audit storage conflict without disabling the production trigger.
        jdbc.update("""
            INSERT INTO approval_audit_events
              (tenant_id,request_id,requester_id,approver_id,outcome)
            VALUES (?,?,?,?,'APPROVED')
            """, tenant, requestId, requester, approver);
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> service.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED));
        assertEquals("PENDING", status());
        assertEquals(0, decisions());
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM approval_audit_events WHERE tenant_id=? AND request_id=?
            """, Long.class, tenant, requestId));
    }

    private boolean tryDecision(CountDownLatch gate, UUID actor, ApprovalDecisionService.Decision decision)
            throws InterruptedException {
        gate.await();
        try {
            service.decide(tenant, actor, requestId, decision);
            return true;
        } catch (IllegalStateException expected) {
            return false;
        }
    }
}
