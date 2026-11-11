# Milestone 01B-3 — Cryptographic JWT trust validation

**Status:** offline test-only hardening. No Keycloak container, accounts, passwords or issued production credentials.

## What is verified

The signed-JWT JUnit suite generates two ephemeral RSA key pairs at test runtime and signs test-only JWTs using RS256.

| Case | Expected |
| --- | --- |
| Trusted signature, correct issuer, correct audience, valid lifetime | Decode succeeds |
| Signature from untrusted RSA key | Rejected |
| Trusted signature, unexpected issuer | Rejected |
| Trusted signature, unrelated audience | Rejected |
| Trusted signature, expired token | Rejected |

The tests exercise Spring Security's `NimbusJwtDecoder`, with explicit issuer, timestamp and audience validators. This is a **real cryptographic test**, unlike the `jwt()` test principal used by HTTP policy tests.

## Scope of evidence

The test configures an in-memory decoder and validation chain; it does not assert that environment-specific Keycloak URLs, discovery metadata or token key rotation work. In the deployed application those depend on the configured issuer/JWKS/audience properties and the actual trusted IdP. No cryptographic credentials are stored in Git.

## What is next

- Bind a real local Keycloak container to the JWT resource server and obtain a token from a dedicated test realm, without exposing credentials or administrative privileges.
- Exercise API calls using actual signed bearer tokens.
- Add key-rotation and token-misuse scenarios; test both authentication (401) and resource authorization (403/404).
- Preserve the full application and PostgreSQL Testcontainers suites with `mvn clean verify`.

**Interview:** JWT signature proves who signed the token under the trusted key; issuer and audience constrain which authority and resource are intended; scope and local employee permissions decide whether a given authenticated user may perform the business action.
