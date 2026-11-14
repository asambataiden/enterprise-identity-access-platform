package dev.asambataiden.iam.security;

import dev.asambataiden.iam.IamApplication;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real signed bearer tokens traverse the application's HTTP security filter.
 * This test supplies a test-only RSA JwtDecoder rather than fetching live JWKS;
 * it does NOT establish production decoder configuration or Keycloak interop.
 */
@SpringBootTest(classes = IamApplication.class)
@Import(SignedJwtHttpFilterIT.TestJwtTrust.class)
@Testcontainers
class SignedJwtHttpFilterIT {
    private static final String ISSUER = "http://127.0.0.1:8281/realms/iam-lab";
    private static final String AUDIENCE = "iam-governance-api";
    private static final String ENDPOINT = "/api/v1/provisioning/quarantine";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("iamdb").withUsername("iam_app").withPassword("test-only-password");

    @DynamicPropertySource
    static void configureDb(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mvc().perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
    }

    @Test
    void validSignatureAndClaimsWithoutRouteScopeAreForbidden() throws Exception {
        mvc().perform(get(ENDPOINT).header("Authorization", "Bearer " + sign(
                TestJwtTrust.trusted, ISSUER, AUDIENCE, "access:request",
                Instant.now().plusSeconds(600))))
                .andExpect(status().isForbidden());
    }

    @Test
    void validScopeWithoutLocalActorPermissionIsForbidden() throws Exception {
        mvc().perform(get(ENDPOINT).header("Authorization", "Bearer " + sign(
                TestJwtTrust.trusted, ISSUER, AUDIENCE, "provisioning:review",
                Instant.now().plusSeconds(600))))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongSignatureIsUnauthorized() throws Exception {
        mvc().perform(get(ENDPOINT).header("Authorization", "Bearer " + sign(
                TestJwtTrust.untrusted, ISSUER, AUDIENCE, "provisioning:review",
                Instant.now().plusSeconds(600))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIsUnauthorized() throws Exception {
        mvc().perform(get(ENDPOINT).header("Authorization", "Bearer " + sign(
                TestJwtTrust.trusted, "http://127.0.0.1:8281/realms/other",
                AUDIENCE, "provisioning:review", Instant.now().plusSeconds(600))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudienceIsUnauthorized() throws Exception {
        mvc().perform(get(ENDPOINT).header("Authorization", "Bearer " + sign(
                TestJwtTrust.trusted, ISSUER, "different-api", "provisioning:review",
                Instant.now().plusSeconds(600))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAccessTokenIsUnauthorized() throws Exception {
        mvc().perform(get(ENDPOINT).header("Authorization", "Bearer " + sign(
                TestJwtTrust.trusted, ISSUER, AUDIENCE, "provisioning:review",
                Instant.now().minusSeconds(3600))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingBearerAndMalformedBearerAreUnauthorized() throws Exception {
        mvc().perform(get(ENDPOINT).header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    private static String sign(KeyPair keys, String issuer, String audience,
                               String scope, Instant expiry) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(audience)
                        .subject("offline-http-test-" + UUID.randomUUID())
                        .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                        .expirationTime(Date.from(expiry))
                        .claim("tenant_id", UUID.randomUUID().toString())
                        .claim("scope", scope)
                        .build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtTrust {
        static final KeyPair trusted = keyPair();
        static final KeyPair untrusted = keyPair();

        private static KeyPair keyPair() {
            try {
                var generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot initialize ephemeral test RSA keypair", e);
            }
        }

        @Bean
        @Primary
        JwtDecoder signedTestJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withPublicKey((RSAPublicKey) trusted.getPublic()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER),
                    new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                            value -> value != null && value.contains(AUDIENCE))));
            return decoder;
        }
    }
}
