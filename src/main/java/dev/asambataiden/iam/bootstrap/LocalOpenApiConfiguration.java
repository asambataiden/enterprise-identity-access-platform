package dev.asambataiden.iam.bootstrap;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Anonymous read-only API documentation for explicit loopback development only.
 *
 * Both the openapi-local profile and application-openapi-local.yml opt-in are
 * required. Without this profile, the main filter's default-deny policy remains
 * unchanged and Springdoc endpoints are disabled.
 *
 * Never activate the profile on a publicly bound or shared deployment.
 */
@Configuration(proxyBeanMethods = false)
@Profile("openapi-local")
public class LocalOpenApiConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain localOpenApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                "/swagger-ui.html", "/swagger-ui/**");
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.httpBasic(basic -> basic.disable());
        http.formLogin(form -> form.disable());
        // Read-only local docs: no mutating requests are permitted even when enabled.
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET,
                        "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                        "/swagger-ui.html", "/swagger-ui/**").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    OpenAPI localOpenApiDescription() {
        return new OpenAPI()
                .info(new Info()
                        .title("Enterprise IAM Governance API")
                        .version("0.1.0-portfolio-contract")
                        .description("Educational, tenant-scoped IAM governance API; "
                                + "APPROVED is not a real external entitlement grant."))
                .components(new Components().addSecuritySchemes(
                        "bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
