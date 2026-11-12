package dev.asambataiden.iam.provisioning;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoopbackHttpProvisioningTargetAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> accepted = new ConcurrentHashMap<>();
    private final AtomicBoolean delayFirstReply = new AtomicBoolean(false);
    private final AtomicBoolean corruptReadback = new AtomicBoolean(false);
    private final AtomicBoolean redirectPost = new AtomicBoolean(false);
    private final AtomicInteger acceptedWrites = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private URI base;
    private ProvisioningTargetAdapter.Entitlement entitlement;
    private String key;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/entitlements", this::handle);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        UUID tenant = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        entitlement = new ProvisioningTargetAdapter.Entitlement(
                tenant, request, UUID.randomUUID(), UUID.randomUUID(), "READER");
        key = ProvisioningAttemptJournal.idempotencyKey(tenant, request);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private LoopbackHttpProvisioningTargetAdapter client(Duration timeout) {
        return new LoopbackHttpProvisioningTargetAdapter(base, timeout);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if ("POST".equals(exchange.getRequestMethod())
                    && "/entitlements".equals(exchange.getRequestURI().getPath())) {
                if (redirectPost.get()) {
                    exchange.getResponseHeaders().set("Location", "http://example.com/");
                    reply(exchange, 302, "");
                    return;
                }
                String supplied = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (supplied == null) {
                    reply(exchange, 400, "");
                    return;
                }
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode data = json.readTree(raw);
                if (data == null || !data.hasNonNull("tenantId") || !data.hasNonNull("requestId")) {
                    reply(exchange, 400, "");
                    return;
                }
                String before = accepted.putIfAbsent(supplied, raw);
                if (before != null && !before.equals(raw)) {
                    reply(exchange, 409, "");
                    return;
                }
                if (before == null) {
                    acceptedWrites.incrementAndGet();
                    if (delayFirstReply.getAndSet(false)) {
                        try {
                            Thread.sleep(350);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                reply(exchange, before == null ? 201 : 200, "");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().equals(
                        "/entitlements/" + entitlement.tenantId() + "/" + entitlement.requestId())) {
                String raw = accepted.get(key);
                if (raw == null) {
                    reply(exchange, 404, "");
                    return;
                }
                if (corruptReadback.get()) {
                    JsonNode changed = json.readTree(raw);
                    ((tools.jackson.databind.node.ObjectNode) changed).put("roleKey", "ADMIN");
                    reply(exchange, 200, json.writeValueAsString(changed));
                } else {
                    reply(exchange, 200, raw);
                }
                return;
            }
            reply(exchange, 404, "");
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length != 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    @Test
    void applyIsIdempotentAndReadbackChecksFullEntitlement() {
        var target = client(Duration.ofSeconds(2));
        assertFalse(target.isEffective(entitlement));
        assertEquals(ProvisioningTargetAdapter.ApplyResult.APPLIED, target.apply(entitlement, key));
        assertTrue(target.isEffective(entitlement));
        assertEquals(ProvisioningTargetAdapter.ApplyResult.ALREADY_PRESENT, target.apply(entitlement, key));
        assertEquals(1, acceptedWrites.get());
        corruptReadback.set(true);
        assertFalse(target.isEffective(entitlement));
    }

    @Test
    void lostAcknowledgementRecoversWithSameStableKey() {
        delayFirstReply.set(true);
        var impatient = client(Duration.ofMillis(100));
        assertThrows(IllegalStateException.class, () -> impatient.apply(entitlement, key));
        // Target persisted the operation before the delayed response was lost.
        assertEquals(1, acceptedWrites.get());
        var retry = client(Duration.ofSeconds(2));
        assertTrue(retry.isEffective(entitlement));
        assertEquals(ProvisioningTargetAdapter.ApplyResult.ALREADY_PRESENT,
                retry.apply(entitlement, key));
        assertEquals(1, acceptedWrites.get());
    }

    @Test
    void redirectAndNonLoopbackEndpointAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new LoopbackHttpProvisioningTargetAdapter(
                        URI.create("http://example.com:8080"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new LoopbackHttpProvisioningTargetAdapter(
                        URI.create("http://127.0.0.1:8080/?next=evil"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new LoopbackHttpProvisioningTargetAdapter(
                        URI.create("https://127.0.0.1:8080"), Duration.ofSeconds(1)));
        redirectPost.set(true);
        var target = client(Duration.ofSeconds(2));
        assertThrows(IllegalStateException.class, () -> target.apply(entitlement, key));
        assertEquals(0, acceptedWrites.get());
    }

    @Test
    void targetNeverTreatsDifferentRoleAsEffective() {
        var target = client(Duration.ofSeconds(2));
        assertEquals(ProvisioningTargetAdapter.ApplyResult.APPLIED, target.apply(entitlement, key));
        var different = new ProvisioningTargetAdapter.Entitlement(
                entitlement.tenantId(), entitlement.requestId(), entitlement.employeeId(),
                entitlement.applicationId(), "ADMIN");
        assertFalse(target.isEffective(different));
    }
}
