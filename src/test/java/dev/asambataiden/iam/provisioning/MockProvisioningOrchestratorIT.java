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
class MockProvisioningOrchestratorIT {
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
    @Autowired MockTargetObservationJournal observations;
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
    void loopbackApplyAndReadbackProduceEvidenceWithoutSuccessTransition() throws Exception {
        approve();
        var lease = leases.claim(tenant, 30).orElseThrow();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var stored = new java.util.concurrent.atomic.AtomicReference<String>();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/entitlements", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if ("POST".equals(exchange.getRequestMethod()) && "/entitlements".equals(path)) {
                    stored.set(new String(exchange.getRequestBody().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
                    writes.incrementAndGet();
                    exchange.sendResponseHeaders(201, -1);
                } else if ("GET".equals(exchange.getRequestMethod())
                        && path.equals("/entitlements/" + tenant + "/" + requestId)
                        && stored.get() != null) {
                    byte[] bytes = stored.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            }
        });
        server.start();
        try {
            var target = new LoopbackHttpProvisioningTargetAdapter(
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    java.time.Duration.ofSeconds(2));
            var orchestrator = new MockProvisioningOrchestrator(journal, observations, jdbc, target);
            assertEquals(MockTargetObservationJournal.Outcome.VERIFIED, orchestrator.execute(lease));
            assertEquals(1, writes.get());
            assertEquals("LEASED", jdbc.queryForObject("""
                SELECT state FROM provisioning_work_items WHERE tenant_id=? AND request_id=?
                """, String.class, tenant, requestId));
            assertEquals("VERIFIED", jdbc.queryForObject("""
                SELECT outcome FROM provisioning_mock_observations
                 WHERE tenant_id=? AND request_id=? AND attempt_number=?
                """, String.class, tenant, requestId, lease.attempt()));
            assertThrows(IllegalStateException.class, () -> orchestrator.execute(lease));
            assertEquals(1, writes.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableLoopbackTargetRecordsUnknownWithoutMarkingSuccess() {
        approve();
        var lease = leases.claim(tenant, 30).orElseThrow();
        // Bind then close the socket to obtain a loopback endpoint that refuses connections.
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        var target = new LoopbackHttpProvisioningTargetAdapter(
                java.net.URI.create("http://127.0.0.1:" + unusedPort),
                java.time.Duration.ofMillis(200));
        var orchestrator = new MockProvisioningOrchestrator(journal, observations, jdbc, target);
        assertEquals(MockTargetObservationJournal.Outcome.UNKNOWN, orchestrator.execute(lease));
        assertEquals("UNKNOWN", jdbc.queryForObject("""
            SELECT outcome FROM provisioning_mock_observations
             WHERE tenant_id=? AND request_id=? AND attempt_number=?
            """, String.class, tenant, requestId, lease.attempt()));
        assertEquals("LEASED", jdbc.queryForObject("""
            SELECT state FROM provisioning_work_items WHERE tenant_id=? AND request_id=?
            """, String.class, tenant, requestId));
    }
}
