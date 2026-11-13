package dev.asambataiden.iam.provisioning;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.asambataiden.iam.accessrequest.ApprovalDecisionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class ProvisioningInboxConsumerIT {
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
    @Autowired ProvisioningInboxConsumer inbox;
    @Autowired TransactionTemplate transactions;

    private ProvisioningInboxConsumer.Message newEvent() {
        UUID tenant=UUID.randomUUID(), requester=UUID.randomUUID(), approver=UUID.randomUUID();
        UUID subject=UUID.randomUUID(), application=UUID.randomUUID(), request=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "inbox-"+tenant);
        for (UUID person : new UUID[]{requester, approver, subject}) {
            jdbc.update("INSERT INTO employees(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", tenant, person);
        }
        jdbc.update("INSERT INTO employee_permissions(tenant_id,employee_id,permission_key) VALUES (?,?,'ACCESS_REQUEST_DECIDE')", tenant, approver);
        jdbc.update("INSERT INTO applications(tenant_id,id,lifecycle_status) VALUES (?,?,'ACTIVE')", tenant, application);
        jdbc.update("INSERT INTO application_roles(tenant_id,application_id,role_key) VALUES (?,?,'READER')", tenant, application);
        jdbc.update("""
            INSERT INTO access_requests(id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
            VALUES (?,?,?,?,?,'READER',?)
            """, request,tenant,requester,subject,application,"inbox-"+request);
        approvals.decide(tenant,approver,request,ApprovalDecisionService.Decision.APPROVED);
        return jdbc.queryForObject("""
            SELECT event_id, tenant_id, request_id, event_type, schema_version, payload::text
              FROM provisioning_outbox_events WHERE tenant_id=? AND request_id=?
            """, (rs, i) -> new ProvisioningInboxConsumer.Message(
                rs.getObject(1, UUID.class),rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),rs.getString(4),rs.getInt(5),rs.getString(6)),
            tenant,request);
    }

    private long receipts(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM provisioning_inbox_receipts WHERE event_id=?", Long.class,id);
    }
    private long projections(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM provisioning_event_projections WHERE event_id=?", Long.class,id);
    }

    @Test
    void firstDeliveryAtomicallyRecordsReceiptAndProjection() {
        var event=newEvent();
        assertEquals(ProvisioningInboxConsumer.Result.APPLIED,inbox.consume(event));
        assertEquals(1L,receipts(event.eventId()));
        assertEquals(1L,projections(event.eventId()));
        assertEquals("RECEIVED", jdbc.queryForObject("""
            SELECT projection_state FROM provisioning_event_projections WHERE event_id=?
            """, String.class,event.eventId()));
    }

    @Test
    void exactReplayDoesNotRepeatBusinessEffect() {
        var event=newEvent();
        assertEquals(ProvisioningInboxConsumer.Result.APPLIED,inbox.consume(event));
        assertEquals(ProvisioningInboxConsumer.Result.DUPLICATE,inbox.consume(event));
        assertEquals(1L,receipts(event.eventId()));
        assertEquals(1L,projections(event.eventId()));
    }

    @Test
    void eventIdCollisionWithChangedTenantIsRejected() {
        var event=newEvent();
        inbox.consume(event);
        UUID foreignTenant = UUID.randomUUID();
        String foreignPayload = event.payload().replace(
            event.tenantId().toString(), foreignTenant.toString());
        var forged=new ProvisioningInboxConsumer.Message(event.eventId(),foreignTenant,
            event.requestId(),event.eventType(),event.schemaVersion(),foreignPayload);
        assertThrows(IllegalStateException.class,()->inbox.consume(forged));
        assertEquals(1L,projections(event.eventId()));
    }

    @Test
    void conflictingPayloadForSameIdentityIsRejected() {
        var event=newEvent();
        inbox.consume(event);
        var changed=new ProvisioningInboxConsumer.Message(event.eventId(),event.tenantId(),
            event.requestId(),event.eventType(),event.schemaVersion(),
            event.payload().substring(0, event.payload().lastIndexOf('}'))
                + ", \"testAttribute\": \"changed\"}");
        assertThrows(IllegalStateException.class,()->inbox.consume(changed));
    }

    @Test
    void malformedEnvelopeCannotCreateEitherRecord() {
        var event=newEvent();
        var invalid=new ProvisioningInboxConsumer.Message(event.eventId(),event.tenantId(),
            event.requestId(),event.eventType(),event.schemaVersion(), "{}");
        assertThrows(DataIntegrityViolationException.class,()->inbox.consume(invalid));
        assertEquals(0L,receipts(event.eventId()));
        assertEquals(0L,projections(event.eventId()));
    }

    @Test
    void rolledBackConsumerLeavesNeitherReceiptNorProjection() {
        var event=newEvent();
        assertThrows(IllegalStateException.class,()->transactions.executeWithoutResult(status->{
            inbox.consume(event);
            assertEquals(1L,receipts(event.eventId()));
            assertEquals(1L,projections(event.eventId()));
            throw new IllegalStateException("rollback test");
        }));
        assertEquals(0L,receipts(event.eventId()));
        assertEquals(0L,projections(event.eventId()));
    }

    @Test
    void concurrentDuplicateDeliveryAppliesBusinessEffectOnlyOnce() throws Exception {
        var event=newEvent();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await(); return inbox.consume(event);});
            var b=pool.submit(()->{gate.await(); return inbox.consume(event);});
            gate.countDown();
            var first=a.get(15,TimeUnit.SECONDS);
            var second=b.get(15,TimeUnit.SECONDS);
            assertTrue((first==ProvisioningInboxConsumer.Result.APPLIED && second==ProvisioningInboxConsumer.Result.DUPLICATE)
                || (second==ProvisioningInboxConsumer.Result.APPLIED && first==ProvisioningInboxConsumer.Result.DUPLICATE));
            assertEquals(1L,receipts(event.eventId()));
            assertEquals(1L,projections(event.eventId()));
        } finally {pool.shutdownNow();}
    }
}
