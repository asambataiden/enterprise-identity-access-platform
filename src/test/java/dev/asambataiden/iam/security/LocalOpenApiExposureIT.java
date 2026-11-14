package dev.asambataiden.iam.security;

import dev.asambataiden.iam.IamApplication;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit profile permits GET-only loopback API documentation without weakening IAM business routes. */
@SpringBootTest(classes = IamApplication.class)
@ActiveProfiles("openapi-local")
@Testcontainers
class LocalOpenApiExposureIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("iamdb")
            .withUsername("iam_app")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void generatedContractIncludesExactlyFourPublicBusinessOperations() throws Exception {
        mvc().perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.paths", aMapWithSize(4)))
                .andExpect(jsonPath("$.paths['/api/v1/access-requests'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/access-requests/{requestId}/decision'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/provisioning/quarantine'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/provisioning/quarantine/{requestId}'].get").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    /**
     * Detect canonical-vs-runtime drift for observable public API semantics.
     *
     * Intentional exclusions: descriptions, operationId naming, component object
     * ordering, server URL, generated error-body shapes, and nullable rendering.
     * These don't change the present business contract or are separately reviewed.
     */
    @Test
    void generatedApiAgreesWithCanonicalBusinessOperationsAndModels() throws Exception {
        String canonicalJson = Files.readString(Path.of("docs/api/openapi.json"));
        String runtimeJson = mvc().perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        DocumentContext canonical = JsonPath.parse(canonicalJson);
        DocumentContext runtime = JsonPath.parse(runtimeJson);

        Map<String, Object> expectedPaths = canonical.read("$.paths");
        Map<String, Object> actualPaths = runtime.read("$.paths");
        assertEquals(expectedPaths.keySet(), actualPaths.keySet(),
                "Runtime changed the public paths; review OpenAPI canonical contract");

        Map<String, String> methods = Map.of(
                "/api/v1/access-requests", "post",
                "/api/v1/access-requests/{requestId}/decision", "post",
                "/api/v1/provisioning/quarantine", "get",
                "/api/v1/provisioning/quarantine/{requestId}", "get"
        );
        for (var entry : methods.entrySet()) {
            String path = entry.getKey();
            String method = entry.getValue();
            String loc = "$.paths['" + path + "']." + method;
            Map<String, Object> expectedOp = canonical.read(loc);
            Map<String, Object> actualOp = runtime.read(loc);
            assertEquals(Set.of(method), ((Map<?, ?>) actualPaths.get(path)).keySet(),
                    "Unexpected HTTP method on " + path);
            assertEquals(((Map<?, ?>) expectedOp.get("responses")).keySet(),
                    ((Map<?, ?>) actualOp.get("responses")).keySet(),
                    "HTTP response-code drift at " + path);
            assertEquals(expectedOp.get("security"), actualOp.get("security"),
                    "JWT bearer security drift at " + path);
            @SuppressWarnings("unchecked")
            var expectedParams = (java.util.List<Map<String, Object>>)
                    expectedOp.getOrDefault("parameters", java.util.List.of());
            @SuppressWarnings("unchecked")
            var actualParams = (java.util.List<Map<String, Object>>)
                    actualOp.getOrDefault("parameters", java.util.List.of());
            for (var ep : expectedParams) {
                var ap = actualParams.stream()
                        .filter(p -> ep.get("name").equals(p.get("name"))
                                && ep.get("in").equals(p.get("in")))
                        .findFirst().orElseThrow(() -> new AssertionError(
                                "Missing parameter: " + path + " " + ep.get("name")));
                assertEquals(ep.get("required"), ap.get("required"),
                        "Required parameter drift at " + path + " " + ep.get("name"));
                @SuppressWarnings("unchecked")
                var expectedSchema = (Map<String, Object>) ep.get("schema");
                @SuppressWarnings("unchecked")
                var actualSchema = (Map<String, Object>) ap.get("schema");
                assertNotNull(actualSchema);
                for (String constraint : java.util.List.of("minimum", "maximum", "default", "minLength", "maxLength")) {
                    if (expectedSchema.containsKey(constraint)) {
                        assertEquals(String.valueOf(expectedSchema.get(constraint)),
                                String.valueOf(actualSchema.get(constraint)),
                                "Parameter constraint " + constraint + " drift at " + path);
                    }
                }
            }
        }

        // Structural field checks protect identity boundaries without relying on
        // generated component names or JSON Schema nullable representation.
        for (String schema : java.util.List.of(
                "CreateAccessRequest", "AccessRequest",
                "DecisionRequest", "DecisionResponse", "QuarantineSnapshot")) {
            String location = "$.components.schemas." + schema;
            Map<String, Object> expected = canonical.read(location);
            Map<String, Object> actual = runtime.read(location);
            assertEquals(((Map<?, ?>) expected.get("properties")).keySet(),
                    ((Map<?, ?>) actual.get("properties")).keySet(),
                    "Property drift in " + schema);
        }
        Map<String, Object> requestFields = runtime.read("$.components.schemas.CreateAccessRequest.properties");
        assertEquals(Set.of("employeeId", "applicationId", "roleKey"), requestFields.keySet(),
                "Tenant/requester must not become client-writable JSON fields");
    }

    @Test
    void developmentUiAndOpenApiReadOnlyAccessAreAvailable() throws Exception {
        int uiCode = mvc().perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertTrue(uiCode == 200 || uiCode == 301 || uiCode == 302 || uiCode == 307,
                "Expected Swagger UI page or redirect, got " + uiCode);
        mvc().perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        int forbidden = mvc().perform(post("/v3/api-docs")).andReturn().getResponse().getStatus();
        assertTrue(forbidden >= 400, "Never allow a writable documentation endpoint");
    }

    @Test
    void protectedBusinessRoutesStillRequireAuthenticatedScope() throws Exception {
        mvc().perform(get("/api/v1/provisioning/quarantine"))
                .andExpect(status().isUnauthorized());
        mvc().perform(get("/actuator/health")).andExpect(status().isOk());
        int unknown = mvc().perform(get("/api/v1/provisioning/lease"))
                .andReturn().getResponse().getStatus();
        assertTrue(unknown >= 400, "Default-deny must remain in force");
    }
}
