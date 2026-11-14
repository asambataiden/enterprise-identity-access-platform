package dev.asambataiden.iam.security;

import dev.asambataiden.iam.IamApplication;
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
