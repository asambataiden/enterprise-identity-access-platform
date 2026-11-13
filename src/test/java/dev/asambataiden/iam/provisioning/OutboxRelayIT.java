package dev.asambataiden.iam.provisioning;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class OutboxRelayIT {
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

    // Every test gets a fresh tenant. Append-only approval audit is left intact.
    private UUID seedApproved() {
        UUID tenant=UUID.randomUUID(), requester=UUID.randomUUID(), approver=UUID.randomUUID();
        UUID subject=UUID.randomUUID(), application=UUID.randomUUID(), request=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)",tenant,"relay-"+tenant);
        for (UUID person : new UUID[]{requester,approver,subject}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",tenant,person);
        }
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')",tenant,approver);
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')",tenant,application);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')",tenant,application);
        jdbc.update("""
            INSERT INTO access_requests(id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER',?)
            """,request,tenant,requester,subject,application,"relay-"+request);
        approvals.decide(tenant,approver,request,ApprovalDecisionService.Decision.APPROVED);
        return tenant;
    }

    private String status(UUID tenant) {
        return jdbc.queryForObject("SELECT delivery_status FROM provisioning_outbox_events WHERE tenant_id=?",
            String.class,tenant);
    }

    @Test
    void relayPublishesAndAcknowledgesExactlyOneEvent() {
        UUID tenant=seedApproved();
        AtomicInteger calls=new AtomicInteger();
        OutboxRelay relay=new OutboxRelay(deliveries,event -> {
            assertEquals(tenant,event.tenantId());
            assertEquals(1,event.schemaVersion());
            assertEquals("PROVISIONING_REQUESTED",event.eventType());
            assertTrue(event.payload().contains("requestId"));
            calls.incrementAndGet();
        });
        assertTrue(relay.runOnce());
        assertEquals(1,calls.get());
        assertEquals("DELIVERED",status(tenant));
        assertFalse(relay.runOnce());
    }

    @Test
    void staleWorkerCannotAcknowledgeRecoveredLease() {
        UUID tenant=seedApproved();
        var first=deliveries.claim(30).orElseThrow();
        jdbc.update("UPDATE provisioning_outbox_events SET lease_expires_at=CURRENT_TIMESTAMP - interval '1 second' WHERE event_id=?",first.eventId());
        var recovered=deliveries.claim(30).orElseThrow();
        assertEquals(first.eventId(),recovered.eventId());
        assertNotEquals(first.leaseToken(),recovered.leaseToken());
        assertEquals(2,recovered.attempt());
        assertFalse(deliveries.delivered(first));
        assertTrue(deliveries.delivered(recovered));
        assertEquals("DELIVERED",status(tenant));
    }

    @Test
    void uncertainPublishIsRetriedWithSameEventId() {
        UUID tenant=seedApproved();
        var first=deliveries.claim(30).orElseThrow();
        assertTrue(deliveries.fail(first,OutboxDeliveryService.FailureCode.PUBLISH_UNAVAILABLE,1));
        assertEquals("RETRY_WAIT",status(tenant));
        jdbc.update("UPDATE provisioning_outbox_events SET next_attempt_at=CURRENT_TIMESTAMP - interval '1 second' WHERE event_id=?",first.eventId());
        var second=deliveries.claim(30).orElseThrow();
        assertEquals(first.eventId(),second.eventId());
        assertEquals(2,second.attempt());
        assertTrue(deliveries.delivered(second));
        assertEquals("DELIVERED",status(tenant));
    }

    @Test
    void fifthFailureMovesEventToDeadLetter() {
        UUID tenant=seedApproved();
        UUID eventId=null;
        for(int i=1;i<=5;i++) {
            var event=deliveries.claim(30).orElseThrow();
            assertEquals(i,event.attempt());
            eventId=event.eventId();
            assertTrue(deliveries.fail(event,OutboxDeliveryService.FailureCode.PUBLISH_UNAVAILABLE,1));
            if(i<5) jdbc.update("UPDATE provisioning_outbox_events SET next_attempt_at=CURRENT_TIMESTAMP - interval '1 second' WHERE event_id=?",eventId);
        }
        assertEquals("DEAD_LETTER",status(tenant));
        assertTrue(deliveries.claim(30).isEmpty());
    }

    @Test
    void expiredFifthLeaseBecomesDeadLetterWithoutSixthPublish() {
        UUID tenant=seedApproved();
        for(int i=1;i<=5;i++) {
            var event=deliveries.claim(30).orElseThrow();
            assertEquals(i,event.attempt());
            jdbc.update("UPDATE provisioning_outbox_events SET lease_expires_at=CURRENT_TIMESTAMP - interval '1 second' WHERE event_id=?",event.eventId());
        }
        assertTrue(deliveries.claim(30).isEmpty());
        assertEquals("DEAD_LETTER",status(tenant));
        assertEquals("LEASE_EXPIRED",jdbc.queryForObject(
            "SELECT last_error_code FROM provisioning_outbox_events WHERE tenant_id=?",String.class,tenant));
    }

    @Test
    void publisherExceptionPersistsFailureWithoutPublishingSuccess() {
        UUID tenant=seedApproved();
        OutboxRelay relay=new OutboxRelay(deliveries,event -> {
            throw new IllegalStateException("simulated lost acknowledgement");
        });
        assertTrue(relay.runOnce());
        assertEquals("RETRY_WAIT",status(tenant));
        assertEquals("PUBLISH_UNAVAILABLE",jdbc.queryForObject(
            "SELECT last_error_code FROM provisioning_outbox_events WHERE tenant_id=?",String.class,tenant));
        assertEquals(1,jdbc.queryForObject(
            "SELECT delivery_attempts FROM provisioning_outbox_events WHERE tenant_id=?",Integer.class,tenant));
    }

    @Test
    void concurrentClaimsCannotSelectSameEvent() throws Exception {
        UUID tenant=seedApproved();
        var pool=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate=new CountDownLatch(1);
            var a=pool.submit(() -> {gate.await();return deliveries.claim(30);});
            var b=pool.submit(() -> {gate.await();return deliveries.claim(30);});
            gate.countDown();
            var one=a.get(15,TimeUnit.SECONDS);
            var two=b.get(15,TimeUnit.SECONDS);
            assertEquals(1,(one.isPresent()?1:0)+(two.isPresent()?1:0));
            assertEquals("IN_FLIGHT",status(tenant));
        } finally {pool.shutdownNow();}
    }
}
