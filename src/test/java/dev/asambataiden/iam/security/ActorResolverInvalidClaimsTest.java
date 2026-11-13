package dev.asambataiden.iam.security;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import dev.asambataiden.iam.accessrequest.DecisionActorResolver;
import dev.asambataiden.iam.accessrequest.RequestActorResolver;
import dev.asambataiden.iam.provisioning.quarantine.QuarantineReviewActorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * IAM-05A-3: test malformed identities at the application authorization boundary.
 *
 * These offline tests intentionally do not simulate signature verification or a full
 * HTTP filter chain. Existing signed-JWT and HTTP integration tests cover those layers.
 */
class ActorResolverInvalidClaimsTest {

    @Test
    void requestActorRejectsMalformedIdentityWithoutDatabaseAccess() throws MalformedURLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        runNegativeIdentityCases(new RequestActorResolver(jdbc)::resolve, jdbc);
    }

    @Test
    void decisionActorRejectsMalformedIdentityWithoutDatabaseAccess() throws MalformedURLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        runNegativeIdentityCases(new DecisionActorResolver(jdbc)::resolve, jdbc);
    }

    @Test
    void quarantineReviewerRejectsMalformedIdentityWithoutDatabaseAccess() throws MalformedURLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        runNegativeIdentityCases(new QuarantineReviewActorResolver(jdbc)::resolve, jdbc);
    }

    private static void runNegativeIdentityCases(Consumer<Jwt> resolve, JdbcTemplate jdbc) throws MalformedURLException {
        List<Jwt> cases = List.of(
                jwt(null, "employee", UUID.randomUUID().toString()),
                jwt(URI.create("https://issuer.example.invalid/iam"), null, UUID.randomUUID().toString()),
                jwt(URI.create("https://issuer.example.invalid/iam"), "   ", UUID.randomUUID().toString()),
                jwt(URI.create("https://issuer.example.invalid/iam"), "employee", null),
                jwt(URI.create("https://issuer.example.invalid/iam"), "employee", ""),
                jwt(URI.create("https://issuer.example.invalid/iam"), "employee", "not-a-uuid")
        );

        // Null JWT and malformed values must fail closed prior to any JDBC operation.
        assertForbidden(() -> resolve.accept(null));
        for (Jwt token : cases) {
            assertForbidden(() -> resolve.accept(token));
        }
        verifyNoInteractions(jdbc);
    }

    private static Jwt jwt(URI issuer, String subject, String tenantId) throws MalformedURLException {
        Jwt token = mock(Jwt.class);
        when(token.getIssuer()).thenReturn(issuer == null ? null : issuer.toURL());
        when(token.getSubject()).thenReturn(subject);
        when(token.getClaimAsString("tenant_id")).thenReturn(tenantId);
        return token;
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable action) {
        ResponseStatusException denied = assertThrows(ResponseStatusException.class, action);
        assertEquals(403, denied.getStatusCode().value());
    }
}
