package dev.asambataiden.iam.accessrequest;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ApprovalDecisionSchemaIT {
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
    UUID tenant, requester, approver, subject, application, requestId;

    @BeforeEach
    void fixtures() {
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
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "tenant-" + tenant);
        for (UUID id : new UUID[]{requester, approver, subject}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", tenant, id);
        }
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",
                tenant, application);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')",
                tenant, application);
        jdbc.update("""
            INSERT INTO access_requests
            (id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER','approval-seed')
            """, requestId, tenant, requester, subject, application);
    }

    private void decide(UUID decisionTenant, UUID decisionRequest, UUID originalRequester,
                        UUID decidingEmployee, String result) {
        jdbc.update("""
            INSERT INTO access_decisions(tenant_id,request_id,requester_id,approver_id,decision)
            VALUES (?,?,?,?,?)
            """, decisionTenant, decisionRequest, originalRequester, decidingEmployee, result);
    }

    @Test
    void distinctApproverCanRecordOneDecisionAndStatusCanTransition() {
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')",
                tenant, approver);
        decide(tenant, requestId, requester, approver, "APPROVED");
        jdbc.update("UPDATE access_requests SET status='APPROVED' WHERE tenant_id=? AND id=?",
                tenant, requestId);
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM access_requests WHERE tenant_id=? AND id=?",
                String.class, tenant, requestId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM access_decisions WHERE tenant_id=? AND request_id=?",
                Long.class, tenant, requestId));
        assertThrows(DataIntegrityViolationException.class,
                () -> decide(tenant, requestId, requester, approver, "REJECTED"),
                "Primary key forbids multiple decisions for one request");
    }

    @Test
    void databaseRejectsSelfApprovalAndForgedRequester() {
        assertThrows(DataIntegrityViolationException.class,
                () -> decide(tenant, requestId, requester, requester, "APPROVED"));
        assertThrows(DataIntegrityViolationException.class,
                () -> decide(tenant, requestId, approver, subject, "APPROVED"));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM access_decisions", Long.class));
    }

    @Test
    void crossTenantApproverAndInvalidDecisionAreRejected() {
        UUID foreign = UUID.randomUUID();
        UUID foreignApprover = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", foreign, "tenant-" + foreign);
        jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", foreign, foreignApprover);
        assertThrows(DataIntegrityViolationException.class,
                () -> decide(tenant, requestId, requester, foreignApprover, "APPROVED"));
        assertThrows(DataIntegrityViolationException.class,
                () -> decide(tenant, requestId, requester, approver, "CANCELLED"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE access_requests SET status='PROVISIONING' WHERE id=?", requestId));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM access_requests WHERE id=?", String.class, requestId));
    }
}
