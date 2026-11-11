package dev.asambataiden.iam.accessrequest;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
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
class AccessRequestPersistenceIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("iamdb")
            .withUsername("iam_app")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AccessRequestService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext webContext;

    @Test
    void anonymousAccessRequestRouteIsDenied() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/access-requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedBearerTokenIsRejected() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/access-requests").header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsAnonymous() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Autowired ApplicationContext context;

    @Test
    void noDefaultInMemoryDevelopmentUserIsCreated() {
        assertTrue(context.getBeansOfType(UserDetailsService.class).isEmpty(),
                "An unused in-memory development account must not be created");
    }

    UUID tenant, actor, employee, application;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM access_requests");
        jdbc.update("DELETE FROM employee_permissions");
        jdbc.update("DELETE FROM employee_identities");
        jdbc.update("DELETE FROM application_roles");
        jdbc.update("DELETE FROM applications");
        jdbc.update("DELETE FROM employees");
        jdbc.update("DELETE FROM tenants");

        tenant = UUID.randomUUID();
        actor = UUID.randomUUID();
        employee = UUID.randomUUID();
        application = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "org-" + tenant);
        jdbc.update("INSERT INTO employees (tenant_id,id,lifecycle_status) VALUES (? ,?, 'ACTIVE')", tenant, actor);
        jdbc.update("INSERT INTO employees (tenant_id,id,lifecycle_status) VALUES (? ,?, 'ACTIVE')", tenant, employee);
        jdbc.update("INSERT INTO applications (tenant_id,id,lifecycle_status) VALUES (?, ?, 'ACTIVE')", tenant, application);
        jdbc.update("INSERT INTO application_roles (tenant_id,application_id,role_key) VALUES (?, ?, ?)", tenant, application, "READER");
    }

    private String requestBody(UUID subject, UUID app) {
        return "{\"employeeId\":\"" + subject + "\",\"applicationId\":\"" + app + "\",\"roleKey\":\"READER\"}";
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authenticated(
            UUID tokenTenant, String subject, boolean scope) {
        var processor = jwt().jwt(builder -> builder
                .issuer("http://127.0.0.1:8081/realms/iam-lab")
                .subject(subject).claim("tenant_id", tokenTenant.toString()));
        return scope ? processor.authorities(new SimpleGrantedAuthority("SCOPE_access:request"))
                : processor.authorities();
    }

    @Test
    void mappedActorWithScopeCanCreateAndRetryOverHttp() throws Exception {
        jdbc.update("INSERT INTO employee_identities (tenant_id,employee_id,issuer,subject) VALUES (?,?,?,?)",
                tenant, actor, "http://127.0.0.1:8081/realms/iam-lab", "admin-user");
        jdbc.update("INSERT INTO employee_permissions (tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_CREATE')",
                tenant, actor);
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(post("/api/v1/access-requests")
                    .with(authenticated(tenant, "admin-user", true))
                    .header("Idempotency-Key", "api-001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(employee, application)))
                    .andExpect(status().isCreated());
        }
        assertEquals(1, count());
    }

    @Test
    void unmappedOrUnpermittedActorIsRejected() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(post("/api/v1/access-requests")
                .with(authenticated(tenant, "admin-user", true))
                .header("Idempotency-Key", "no-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(employee, application)))
                .andExpect(status().isForbidden());
        assertEquals(0, count());
    }

    @Test
    void tokenWithMissingRequiredScopeIsRejected() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(post("/api/v1/access-requests")
                .with(authenticated(tenant, "admin-user", false))
                .header("Idempotency-Key", "no-scope")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(employee, application)))
                .andExpect(status().isForbidden());
        assertEquals(0, count());
    }

    @Test
    void tenantHeaderCannotOverrideAuthenticatedActor() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(post("/api/v1/access-requests")
                .with(authenticated(UUID.randomUUID(), "admin-user", true))
                .header("X-Tenant-Id", tenant.toString())
                .header("X-Requester-Id", actor.toString())
                .header("Idempotency-Key", "spoof")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(employee, application)))
                .andExpect(status().isForbidden());
        assertEquals(0, count());
    }

    @Test
    void createsPendingRequestAndReturnsSameRowOnRetry() {
        AccessRequest first = service.create(tenant, actor, employee, application, "READER", "req-001");
        AccessRequest retry = service.create(tenant, actor, employee, application, "READER", "req-001");
        assertEquals("PENDING", first.status());
        assertEquals(first.id(), retry.id());
        assertEquals(1, count());
    }

    @Test
    void changedTermsForSameTenantKeyAreRejected() {
        service.create(tenant, actor, employee, application, "READER", "req-001");
        assertThrows(AccessRequestConflictException.class,
                () -> service.create(tenant, actor, actor, application, "READER", "req-001"));
        assertEquals(1, count());
    }

    @Test
    void foreignTenantEmployeeCannotBeReferenced() {
        UUID differentTenant = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", differentTenant, "org-" + differentTenant);
        jdbc.update("INSERT INTO employees (tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", differentTenant, outsider);
        assertThrows(IllegalArgumentException.class,
                () -> service.create(tenant, actor, outsider, application, "READER", "req-cross"));
        assertEquals(0, count());
    }

    @Test
    void disabledEmployeeCannotBeGrantedPendingRequest() {
        jdbc.update("UPDATE employees SET lifecycle_status='DISABLED' WHERE tenant_id=? AND id=?", tenant, employee);
        assertThrows(IllegalArgumentException.class,
                () -> service.create(tenant, actor, employee, application, "READER", "req-disabled"));
        assertEquals(0, count());
    }

    @Test
    void parallelEquivalentRetriesProduceOneLogicalRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch ready = new CountDownLatch(6);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = IntStream.range(0, 6).<Callable<AccessRequest>>mapToObj(i -> () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("start gate");
                return service.create(tenant, actor, employee, application, "READER", "concurrent-key");
            }).toList();
            var submitted = tasks.stream().map(pool::submit).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            UUID winner = null;
            for (var result : submitted) {
                UUID id = result.get(30, TimeUnit.SECONDS).id();
                if (winner == null) winner = id;
                assertEquals(winner, id);
            }
            assertEquals(1, count());
        } finally {
            pool.shutdownNow();
        }
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM access_requests", Integer.class);
    }
}
