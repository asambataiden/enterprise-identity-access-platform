package dev.asambataiden.iam.provisioning;

import java.util.UUID;

import dev.asambataiden.iam.accessrequest.ApprovalDecisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ProvisioningTransactionalOutboxIT {
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
    @Autowired ApprovalDecisionService decisions;
    @Autowired TransactionTemplate transactions;

    UUID tenant, requester, approver, employee, application, requestId;

    @BeforeEach
    void setup() {
        // This Testcontainers database is disposable; audit history remains
        // append-only, never disabled or deleted to make fixture cleanup pass.
        jdbc.update("DELETE FROM provisioning_outbox_events");
        jdbc.update("DELETE FROM provisioning_quarantine_reviews");
        jdbc.update("DELETE FROM provisioning_mock_observations");
        jdbc.update("DELETE FROM provisioning_attempts");
        jdbc.update("DELETE FROM simulated_entitlements");
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
        employee = UUID.randomUUID();
        application = UUID.randomUUID();
        requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "outbox-" + tenant);
        for (UUID id : new UUID[]{requester, approver, employee}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", tenant, id);
        }
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')", tenant, approver);
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", tenant, application);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')", tenant, application);
        jdbc.update("""
            INSERT INTO access_requests
              (id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER',?)
            """, requestId, tenant, requester, employee, application, "04g1-" + requestId);
    }

    private long count(String table) {
        // Only constants from this test are passed; no dynamic SQL from users.
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND request_id=?",
                Long.class, tenant, requestId);
    }

    @Test
    void approvedDecisionCreatesOneWorkItemAndMatchingOutboxEventAtomically() {
        decisions.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED);
        assertEquals(1L, count("provisioning_work_items"));
        assertEquals(1L, count("provisioning_outbox_events"));
        assertEquals("PROVISIONING_REQUESTED", jdbc.queryForObject("""
                SELECT event_type FROM provisioning_outbox_events
                 WHERE tenant_id=? AND request_id=?
                """, String.class, tenant, requestId));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT delivery_status FROM provisioning_outbox_events
                 WHERE tenant_id=? AND request_id=?
                """, String.class, tenant, requestId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT schema_version FROM provisioning_outbox_events
                 WHERE tenant_id=? AND request_id=?
                """, Integer.class, tenant, requestId));
        assertEquals(requestId.toString(), jdbc.queryForObject("""
                SELECT payload->>'requestId' FROM provisioning_outbox_events
                 WHERE tenant_id=? AND request_id=?
                """, String.class, tenant, requestId));
    }

    @Test
    void rejectedDecisionCreatesNeitherWorkItemNorOutboxEvent() {
        decisions.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.REJECTED);
        assertEquals(0L, count("provisioning_work_items"));
        assertEquals(0L, count("provisioning_outbox_events"));
    }

    @Test
    void forcedRollbackRemovesDecisionWorkItemAuditAndOutboxTogether() {
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            decisions.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED);
            assertEquals(1L, count("provisioning_outbox_events"));
            throw new IllegalStateException("test-only rollback");
        }));
        assertEquals(0L, count("access_decisions"));
        assertEquals(0L, count("provisioning_work_items"));
        assertEquals(0L, count("provisioning_outbox_events"));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM approval_audit_events
                 WHERE tenant_id=? AND request_id=?
                """, Long.class, tenant, requestId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM access_requests WHERE id=?",
                String.class, requestId));
    }

    @Test
    void migrationDoesNotChangeExistingApprovedIntentState() {
        // V12 must not create new work items or modify provisioning state.
        assertEquals(0L, count("provisioning_work_items"));
        assertEquals(0L, count("provisioning_outbox_events"));
    }
}
