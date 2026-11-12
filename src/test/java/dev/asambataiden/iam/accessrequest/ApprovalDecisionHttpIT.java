package dev.asambataiden.iam.accessrequest;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest
@Testcontainers
class ApprovalDecisionHttpIT {
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
    UUID tenant, requester, approver, employee, app, request;

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
        tenant=UUID.randomUUID(); requester=UUID.randomUUID(); approver=UUID.randomUUID();
        employee=UUID.randomUUID(); app=UUID.randomUUID(); request=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "http-" + tenant);
        for (UUID id: new UUID[]{requester, approver, employee}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",tenant,id);
        }
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",tenant,app);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')",tenant,app);
        jdbc.update("""
            INSERT INTO access_requests(id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER','http-decide')
            """,request,tenant,requester,employee,app);
        bind(approver,"approver",true);
        bind(requester,"requester",false);
    }

    private void bind(UUID id, String subject, boolean decide) {
        jdbc.update("INSERT INTO employee_identities(tenant_id,employee_id,issuer,subject) VALUES (?,?,?,?)",
                tenant,id,ISSUER,subject);
        if(decide) {
            jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')",tenant,id);
        }
    }

    private RequestPostProcessor principal(UUID tokenTenant, String subject, String scope) {
        var mock=jwt().jwt(b -> b.issuer(ISSUER).subject(subject).claim("tenant_id",tokenTenant.toString()));
        return scope == null ? mock.authorities() :
                mock.authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
    }

    private long decisions() {
        return jdbc.queryForObject("SELECT count(*) FROM access_decisions",Long.class);
    }
    private String state() {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id=?",String.class,request);
    }
    private void unchanged() {
        assertEquals(0,decisions());
        assertEquals("PENDING",state());
    }

    private org.springframework.test.web.servlet.MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void authenticatedApproverWithScopeAndLocalPermissionCanApprove() throws Exception {
        mvc().perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(tenant,"approver","access:decide"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("APPROVED"));
        assertEquals("APPROVED",state());
        assertEquals(1,decisions());
    }

    @Test
    void authenticatedApproverCanRejectAndDuplicateIsConflict() throws Exception {
        mvc().perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(tenant,"approver","access:decide"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\"}"))
                .andExpect(status().isOk());
        mvc().perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(tenant,"approver","access:decide"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\"}"))
                .andExpect(status().isConflict());
        assertEquals("REJECTED",state());
        assertEquals(1,decisions());
    }

    @Test
    void anonymousAndWrongScopeDeniedBeforeWrite() throws Exception {
        var m=mvc();
        m.perform(post("/api/v1/access-requests/{id}/decision",request)
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isUnauthorized());
        m.perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(tenant,"approver","access:request"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
        unchanged();
    }

    @Test
    void noPermissionRevokedAndSpoofedTenantDenied() throws Exception {
        jdbc.update("DELETE FROM employee_permissions WHERE employee_id=?",approver);
        mvc().perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(tenant,"approver","access:decide"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
        mvc().perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(UUID.randomUUID(),"approver","access:decide"))
                .header("X-Tenant-Id",tenant.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
        unchanged();
    }

    @Test
    void selfApprovalDeniedEvenIfRequesterHasPermission() throws Exception {
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')",tenant,requester);
        mvc().perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(tenant,"requester","access:decide"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
        unchanged();
    }

    @Test
    void foreignTenantRequestAndUnknownRequestAreNotDisclosed() throws Exception {
        UUID foreignTenant=UUID.randomUUID(), foreignApprover=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)",foreignTenant,"foreign-"+foreignTenant);
        jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",foreignTenant,foreignApprover);
        jdbc.update("INSERT INTO employee_identities(tenant_id,employee_id,issuer,subject) VALUES (?,?,?,?)",
                foreignTenant,foreignApprover,ISSUER,"foreign");
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')",foreignTenant,foreignApprover);
        mvc().perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(foreignTenant,"foreign","access:decide"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());
        mvc().perform(post("/api/v1/access-requests/{id}/decision",UUID.randomUUID())
                .with(principal(tenant,"approver","access:decide"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());
        unchanged();
    }

    @Test
    void unsupportedOrMissingDecisionCannotChangeRequest() throws Exception {
        mvc().perform(post("/api/v1/access-requests/{id}/decision",request)
                .with(principal(tenant,"approver","access:decide"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        unchanged();
    }
}
