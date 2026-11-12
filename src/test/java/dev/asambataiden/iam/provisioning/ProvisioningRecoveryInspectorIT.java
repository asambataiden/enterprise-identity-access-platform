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
class ProvisioningRecoveryInspectorIT {
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
    @Autowired ProvisioningRecoveryInspector inspector;
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
    void quarantineInspectionReportsTenantScopedFactsWithoutMutatingWork() {
        approve();
        var first = leases.claim(tenant, 30).orElseThrow();
        assertTrue(journal.start(first));
        assertTrue(leases.fail(first, ProvisioningLeaseService.FailureCode.TIMEOUT, 1));
        for (int attempt=2; attempt<=3; attempt++) {
            jdbc.update("""
                UPDATE provisioning_work_items SET next_attempt_at=CURRENT_TIMESTAMP - interval '1 second'
                WHERE tenant_id=? AND request_id=?
                """, tenant, requestId);
            var next = leases.claim(tenant,30).orElseThrow();
            assertTrue(leases.fail(next, ProvisioningLeaseService.FailureCode.UNKNOWN, 1));
        }
        var snapshot = inspector.inspect(tenant, requestId).orElseThrow();
        assertEquals("QUARANTINED", snapshot.state());
        assertEquals(3, snapshot.attempts());
        assertEquals("UNKNOWN", snapshot.lastErrorCode());
        assertEquals(1, snapshot.startedAttempts());
        assertEquals(0, snapshot.mockVerified());
        assertEquals(0, snapshot.mockAbsent());
        assertEquals(0, snapshot.mockUnknown());
        assertNull(snapshot.leaseExpiresAt());
        assertEquals(1, inspector.quarantined(tenant, 10).size());
        assertEquals(requestId, inspector.quarantined(tenant, 10).getFirst().requestId());
        assertTrue(inspector.inspect(UUID.randomUUID(), requestId).isEmpty());
        assertTrue(inspector.quarantined(UUID.randomUUID(), 10).isEmpty());
        assertEquals("QUARANTINED", inspector.inspect(tenant, requestId).orElseThrow().state());
        assertTrue(leases.claim(tenant,30).isEmpty());
    }

    @Test
    void inspectionValidatesTenantAndBoundedLimitAndShowsNoUnapprovedWork() {
        assertTrue(inspector.inspect(tenant, requestId).isEmpty());
        assertTrue(inspector.quarantined(tenant, 100).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(null, requestId));
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(tenant, null));
        assertThrows(IllegalArgumentException.class, () -> inspector.quarantined(null, 1));
        assertThrows(IllegalArgumentException.class, () -> inspector.quarantined(tenant, 0));
        assertThrows(IllegalArgumentException.class, () -> inspector.quarantined(tenant, 101));
    }
}
