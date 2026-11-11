package dev.asambataiden.iam.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline cryptographic validation against test-only RSA keys; not a live
 * Keycloak interoperability or full HTTP security configuration test.
 */
class SignedJwtTrustValidationTest {
    private static final String ISSUER = "http://127.0.0.1:8081/realms/iam-lab";
    private static final String AUDIENCE = "iam-governance-api";
    private static KeyPair trusted;
    private static KeyPair untrusted;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void initKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trusted = generator.generateKeyPair();
        untrusted = generator.generateKeyPair();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) trusted.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        value -> value != null && value.contains(AUDIENCE))));
    }

    @Test
    void validSignedTokenIsDecoded() throws Exception {
        Jwt jwt = decoder.decode(sign(trusted, ISSUER, AUDIENCE, Instant.now().plusSeconds(600)));
        assertEquals("test-admin-subject", jwt.getSubject());
        assertEquals(ISSUER, jwt.getIssuer().toString());
        assertTrue(jwt.getAudience().contains(AUDIENCE));
    }

    @Test
    void tokenWithUnknownSigningKeyIsRejected() throws Exception {
        assertThrows(JwtException.class,
                () -> decoder.decode(sign(untrusted, ISSUER, AUDIENCE, Instant.now().plusSeconds(600))));
    }

    @Test
    void validSignatureWithWrongIssuerIsRejected() throws Exception {
        assertThrows(JwtException.class,
                () -> decoder.decode(sign(trusted, "http://127.0.0.1:8081/realms/other",
                        AUDIENCE, Instant.now().plusSeconds(600))));
    }

    @Test
    void validSignatureWithWrongAudienceIsRejected() throws Exception {
        assertThrows(JwtException.class,
                () -> decoder.decode(sign(trusted, ISSUER, "unrelated-service", Instant.now().plusSeconds(600))));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        assertThrows(JwtException.class,
                () -> decoder.decode(sign(trusted, ISSUER, AUDIENCE, Instant.now().minusSeconds(3600))));
    }

    private static String sign(KeyPair key, String issuer, String audience, Instant expiry) throws Exception {
        Instant now = Instant.now();
        SignedJWT signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject("test-admin-subject")
                        .audience(audience)
                        .issueTime(Date.from(now.minusSeconds(7200)))
                        .expirationTime(Date.from(expiry))
                        .claim("scope", "access:request")
                        .build());
        signed.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return signed.serialize();
    }
}
