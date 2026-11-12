package dev.asambataiden.iam.provisioning;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deliberately loopback-only HTTP client for a local mock provisioning target.
 *
 * Not annotated as a Spring bean and never configured for real IAM systems.
 * No automatic retry, redirect, credential, or network discovery is permitted.
 */
public final class LoopbackHttpProvisioningTargetAdapter implements ProvisioningTargetAdapter {
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final URI base;
    private final Duration timeout;

    public LoopbackHttpProvisioningTargetAdapter(URI base, Duration timeout) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(timeout, "timeout");
        if (!"http".equals(base.getScheme()) ||
                !"127.0.0.1".equals(base.getHost()) ||
                base.getPort() < 1 || base.getPort() > 65535 ||
                !(base.getPath().isEmpty() || "/".equals(base.getPath())) ||
                base.getRawQuery() != null || base.getRawFragment() != null ||
                base.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Only loopback HTTP mock root URI is allowed");
        }
        if (timeout.compareTo(Duration.ofMillis(100)) < 0 ||
                timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Mock HTTP timeout outside 100ms..5s");
        }
        this.base = base;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ApplyResult apply(Entitlement entitlement, String idempotencyKey) {
        Objects.requireNonNull(entitlement, "entitlement");
        if (idempotencyKey == null || !idempotencyKey.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("Invalid stable idempotency key");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/entitlements"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(entitlement))))
                    .build();
            HttpResponse<String> response = send(request);
            if (response.statusCode() == 201) {
                return ApplyResult.APPLIED;
            }
            if (response.statusCode() == 200) {
                return ApplyResult.ALREADY_PRESENT;
            }
            throw new IllegalStateException("Mock target rejected provisioning: HTTP " + response.statusCode());
        } catch (IOException exception) {
            throw new IllegalStateException("Mock HTTP request failed or response was lost", exception);
        }
    }

    @Override
    public boolean isEffective(Entitlement entitlement) {
        Objects.requireNonNull(entitlement, "entitlement");
        try {
            URI endpoint = base.resolve("/entitlements/" + entitlement.tenantId() +
                    "/" + entitlement.requestId());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = send(request);
            if (response.statusCode() == 404) {
                return false;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mock readback failed: HTTP " + response.statusCode());
            }
            if (response.body().length() > 4096) {
                throw new IllegalStateException("Mock readback exceeds size limit");
            }
            JsonNode readback = json.readTree(response.body());
            return entitlement.tenantId().toString().equals(readback.path("tenantId").asText())
                    && entitlement.requestId().toString().equals(readback.path("requestId").asText())
                    && entitlement.employeeId().toString().equals(readback.path("employeeId").asText())
                    && entitlement.applicationId().toString().equals(readback.path("applicationId").asText())
                    && entitlement.roleKey().equals(readback.path("roleKey").asText());
        } catch (IOException exception) {
            throw new IllegalStateException("Mock HTTP readback failed or timed out", exception);
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock HTTP interrupted", exception);
        }
    }

    private static Map<String, String> payload(Entitlement entitlement) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("tenantId", entitlement.tenantId().toString());
        fields.put("requestId", entitlement.requestId().toString());
        fields.put("employeeId", entitlement.employeeId().toString());
        fields.put("applicationId", entitlement.applicationId().toString());
        fields.put("roleKey", entitlement.roleKey());
        return fields;
    }
}
