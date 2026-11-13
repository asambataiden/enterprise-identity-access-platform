package dev.asambataiden.iam.provisioning.outbox;

import dev.asambataiden.iam.provisioning.inbox.ProvisioningInboxConsumer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.asambataiden.iam.accessrequest.ApprovalDecisionService;
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
class OutboxInboxEndToEndIT {
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
    @Autowired ApprovalDecisionService approvals;
    @Autowired OutboxDeliveryService deliveries;
    @Autowired ProvisioningInboxConsumer inbox;

    private record Fixture(UUID tenant, UUID request, UUID event) {}

    private Fixture approvedRequest() {
        UUID tenant = UUID.randomUUID(), requester = UUID.randomUUID(), approver = UUID.randomUUID();
        UUID employee = UUID.randomUUID(), app = UUID.randomUUID(), request = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "e2e-" + tenant);
        for (UUID person : new UUID[]{requester, approver, employee}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", tenant, person);
        }
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')", tenant, approver);
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", tenant, app);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')", tenant, app);
        jdbc.update("""
            INSERT INTO access_requests(id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER',?)
            """, request, tenant, requester, employee, app, "e2e-" + request);
        approvals.decide(tenant, approver, request, ApprovalDecisionService.Decision.APPROVED);
        UUID event = jdbc.queryForObject("""
            SELECT event_id FROM provisioning_outbox_events WHERE tenant_id=? AND request_id=?
            """, UUID.class, tenant, request);
        return new Fixture(tenant, request, event);
    }

    private long count(String table, Fixture f) {
        // Only test-owned constant table names are passed to this query.
        return jdbc.queryForObject("SELECT count(*) FROM " + table +
                " WHERE tenant_id=? AND request_id=?", Long.class, f.tenant(), f.request());
    }

    private String status(Fixture f) {
        return jdbc.queryForObject("""
            SELECT delivery_status FROM provisioning_outbox_events WHERE event_id=?
            """, String.class, f.event());
    }

    @Test
    void approvalToDurableInboxProjectionCompletes() {
        Fixture f = approvedRequest();
        assertEquals(1L, count("provisioning_work_items", f));
        assertEquals(1L, count("provisioning_outbox_events", f));
        assertEquals("PENDING", status(f));

        var relay = new OutboxRelay(deliveries, new InProcessInboxPublisher(inbox));
        assertTrue(relay.runOnce());
        assertEquals("DELIVERED", status(f));
        assertEquals(1L, count("provisioning_inbox_receipts", f));
        assertEquals(1L, count("provisioning_event_projections", f));
        assertEquals("RECEIVED", jdbc.queryForObject("""
            SELECT projection_state FROM provisioning_event_projections WHERE event_id=?
            """, String.class, f.event()));
        assertFalse(relay.runOnce());
        // This projection does not prove an entitlement was provisioned.
    }

    @Test
    void lostPublisherAcknowledgementRedeliversButDoesNotDuplicateProjection() {
        Fixture f = approvedRequest();
        var local = new InProcessInboxPublisher(inbox);
        AtomicInteger calls = new AtomicInteger();
        var relay = new OutboxRelay(deliveries, event -> {
            local.publish(event); // receipt and projection have already committed
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("test-only: acknowledgement lost");
            }
        });
        assertTrue(relay.runOnce());
        assertEquals("RETRY_WAIT", status(f));
        assertEquals(1L, count("provisioning_inbox_receipts", f));
        assertEquals(1L, count("provisioning_event_projections", f));
        jdbc.update("""
            UPDATE provisioning_outbox_events SET next_attempt_at=CURRENT_TIMESTAMP - interval '1 second'
            WHERE event_id=? AND delivery_status='RETRY_WAIT'
            """, f.event());
        assertTrue(relay.runOnce());
        assertEquals(2, calls.get());
        assertEquals("DELIVERED", status(f));
        assertEquals(2, jdbc.queryForObject("""
            SELECT delivery_attempts FROM provisioning_outbox_events WHERE event_id=?
            """, Integer.class, f.event()));
        assertEquals(1L, count("provisioning_inbox_receipts", f));
        assertEquals(1L, count("provisioning_event_projections", f));
    }

    @Test
    void consumerUnavailableThenRecoversWithoutPrematureAck() {
        Fixture f = approvedRequest();
        var local = new InProcessInboxPublisher(inbox);
        AtomicInteger calls = new AtomicInteger();
        var relay = new OutboxRelay(deliveries, event -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("test-only: consumer temporarily unavailable");
            }
            local.publish(event);
        });
        assertTrue(relay.runOnce());
        assertEquals("RETRY_WAIT", status(f));
        assertEquals(0L, count("provisioning_inbox_receipts", f));
        assertEquals(0L, count("provisioning_event_projections", f));
        jdbc.update("""
            UPDATE provisioning_outbox_events SET next_attempt_at=CURRENT_TIMESTAMP - interval '1 second'
            WHERE event_id=? AND delivery_status='RETRY_WAIT'
            """, f.event());
        assertTrue(relay.runOnce());
        assertEquals("DELIVERED", status(f));
        assertEquals(1L, count("provisioning_inbox_receipts", f));
        assertEquals(1L, count("provisioning_event_projections", f));
    }
}
