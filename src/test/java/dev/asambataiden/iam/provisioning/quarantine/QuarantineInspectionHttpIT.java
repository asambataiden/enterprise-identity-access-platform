package dev.asambataiden.iam.provisioning.quarantine;

import dev.asambataiden.iam.provisioning.execution.ProvisioningLeaseService;
import java.util.UUID;

import dev.asambataiden.iam.accessrequest.ApprovalDecisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest
@Testcontainers
class QuarantineInspectionHttpIT {
    private static final String ISSUER = "http://127.0.0.1:8281/realms/iam-lab";

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
    @Autowired WebApplicationContext context;
    @Autowired ApprovalDecisionService decisions;
    @Autowired ProvisioningLeaseService leases;
    UUID tenant, requester, approver, reviewer, subject, application, requestId;

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
        reviewer = UUID.randomUUID();
        subject = UUID.randomUUID();
        application = UUID.randomUUID();
        requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "review-" + tenant);
        for (UUID employee : new UUID[]{requester, approver, reviewer, subject}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",
                    tenant, employee);
        }
        jdbc.update("INSERT INTO employee_identities(tenant_id,employee_id,issuer,subject) VALUES (?,?,?,?)",
                tenant, reviewer, ISSUER, "quarantine-reviewer");
        jdbc.update("""
            INSERT INTO employee_permissions(tenant_id,employee_id,permission_key)
            VALUES (?,?,'ACCESS_REQUEST_DECIDE')
            """, tenant, approver);
        jdbc.update("""
            INSERT INTO employee_permissions(tenant_id,employee_id,permission_key)
            VALUES (?,?,'PROVISIONING_QUARANTINE_REVIEW')
            """, tenant, reviewer);
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",
                tenant, application);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')",
                tenant, application);
        jdbc.update("""
            INSERT INTO access_requests
              (id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER','quarantine-http')
            """, requestId, tenant, requester, subject, application);
    }

    private org.springframework.test.web.servlet.MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private RequestPostProcessor principal(UUID tokenTenant, String subject, String scope) {
        var auth = jwt().jwt(jwt -> jwt.issuer(ISSUER).subject(subject)
                .claim("tenant_id", tokenTenant.toString()));
        return auth.authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
    }

    private void quarantine() {
        decisions.decide(tenant, approver, requestId, ApprovalDecisionService.Decision.APPROVED);
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
    void authorizedReviewerSeesOnlyReadOnlyQuarantineData() throws Exception {
        quarantine();
        var m = mvc();
        m.perform(get("/api/v1/provisioning/quarantine")
                .with(principal(tenant, "quarantine-reviewer", "provisioning:review")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].state").value("QUARANTINED"));
        m.perform(get("/api/v1/provisioning/quarantine/{requestId}", requestId)
                .with(principal(tenant, "quarantine-reviewer", "provisioning:review")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(3));
        assertEquals("QUARANTINED", jdbc.queryForObject(
                "SELECT state FROM provisioning_work_items WHERE tenant_id=? AND request_id=?",
                String.class, tenant, requestId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM provisioning_quarantine_reviews", Long.class));
    }

    @Test
    void anonymousMissingScopeOrMissingLocalPermissionCannotInspect() throws Exception {
        quarantine();
        var m = mvc();
        m.perform(get("/api/v1/provisioning/quarantine")).andExpect(status().isUnauthorized());
        m.perform(get("/api/v1/provisioning/quarantine")
                .with(principal(tenant, "quarantine-reviewer", "access:decide")))
                .andExpect(status().isForbidden());
        jdbc.update("""
            DELETE FROM employee_permissions
             WHERE tenant_id=? AND employee_id=? AND permission_key='PROVISIONING_QUARANTINE_REVIEW'
            """, tenant, reviewer);
        m.perform(get("/api/v1/provisioning/quarantine")
                .with(principal(tenant, "quarantine-reviewer", "provisioning:review")))
                .andExpect(status().isForbidden());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM provisioning_quarantine_reviews", Long.class));
    }

    @Test
    void wrongTenantAndUnknownRequestAreNotDisclosed() throws Exception {
        quarantine();
        var m = mvc();
        m.perform(get("/api/v1/provisioning/quarantine/{requestId}", requestId)
                .header("X-Tenant-Id", tenant.toString())
                .with(principal(UUID.randomUUID(), "quarantine-reviewer", "provisioning:review")))
                .andExpect(status().isForbidden());
        m.perform(get("/api/v1/provisioning/quarantine/{requestId}", UUID.randomUUID())
                .with(principal(tenant, "quarantine-reviewer", "provisioning:review")))
                .andExpect(status().isNotFound());
        m.perform(get("/api/v1/provisioning/quarantine")
                .param("limit", "101")
                .with(principal(tenant, "quarantine-reviewer", "provisioning:review")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledReviewerAndUnquarantinedRequestAreNotDisclosed() throws Exception {
        var m = mvc();
        m.perform(get("/api/v1/provisioning/quarantine/{requestId}", requestId)
                .with(principal(tenant, "quarantine-reviewer", "provisioning:review")))
                .andExpect(status().isNotFound());
        quarantine();
        jdbc.update("UPDATE employees SET lifecycle_status='DISABLED' WHERE tenant_id=? AND id=?",
                tenant, reviewer);
        m.perform(get("/api/v1/provisioning/quarantine")
                .with(principal(tenant, "quarantine-reviewer", "provisioning:review")))
                .andExpect(status().isForbidden());
    }
}
