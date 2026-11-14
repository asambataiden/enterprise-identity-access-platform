package dev.asambataiden.iam.security;

import dev.asambataiden.iam.IamApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without an opt-in profile, the documentation endpoints must not be publicly accessible. */
@SpringBootTest(classes = IamApplication.class)
@Testcontainers
class OpenApiDisabledByDefaultIT {
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
    void anonymousDocumentationAndUiCannotBeAccessed() throws Exception {
        for (String url : new String[]{
                "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                "/swagger-ui.html", "/swagger-ui/index.html"}) {
            int code = mvc().perform(get(url)).andReturn().getResponse().getStatus();
            assertTrue(code >= 400 && code < 500, "Expected default-denied " + url + " got " + code);
        }
    }

    @Test
    void originalHealthAndBusinessSecurityRemain() throws Exception {
        mvc().perform(get("/actuator/health")).andExpect(status().isOk());
        mvc().perform(get("/api/v1/provisioning/quarantine"))
                .andExpect(status().isUnauthorized());
        int code = mvc().perform(post("/v3/api-docs")).andReturn().getResponse().getStatus();
        assertTrue(code >= 400, "No mutable documentation endpoint may be exposed");
    }
}
