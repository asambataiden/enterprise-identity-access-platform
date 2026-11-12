# Milestone 01B-1 — JWT resource-server security boundary

> **Historical design note:** Original stage/status statements below describe the milestone at authoring time. For post-merge verification, local failures and fixes, and interview-ready evidence see [the engineering implementation ledger](../learning/iam-engineering-implementation-and-interview-ledger.md).

**Status:** initial security configuration; **not** a complete authenticated IAM access-request API.

## Scope and rationale

Spring Security validates bearer JWTs using a trusted authorization-server issuer, a JWKS endpoint and the `iam-governance-api` audience. The Java application does **not** mint access tokens or store user passwords. The Keycloak issuer and JWKS are local-development defaults only; verify and override with trusted, TLS-protected endpoints for any nonlocal environment.

HTTP requests are stateless: no user sessions, HTTP Basic or form login. The only anonymous route is `GET /actuator/health`. Access-request routes require `SCOPE_access:request`; all other routes deny by default. As of this stage, **no access-request controller has been implemented**, so neither the existence nor the scope check grants business functionality.

## Trust boundaries

A JWT's signature, issuer, expiration, not-before and audience require cryptographic validation. Possession of `SCOPE_access:request` must later be combined with server-side policy evaluation: tenant membership, active requester, employee and application, role eligibility, and explicit denial of self-approval. Never accept an arbitrary `tenant_id` or `requester_id` from the JSON request body as proof of identity.

**Resource server is not identity-provider integration testing.** Negative HTTP tests exercise authorization filters without requiring a running Keycloak container. Full signed-JWT and live-Keycloak interoperability tests belong to a later distinct test scope.

## Configuration

The development-only defaults are `IAM_JWT_ISSUER_URI=http://127.0.0.1:8081/realms/iam-lab`, `IAM_JWT_JWKS_URI=http://127.0.0.1:8081/realms/iam-lab/protocol/openid-connect/certs`, and `IAM_JWT_AUDIENCE=iam-governance-api`. They do not imply that Keycloak is currently running. Replace these values in deployment with a reachable trusted HTTPS issuer and its correct published keys.

## Validation

```bash
mvn -B --no-transfer-progress clean verify
```

Tests verify that missing or malformed bearer credentials do not permit access requests and that the health route remains accessible. Integration tests continue to verify the V1 tenant-safe database schema.

**Known limits:** No API endpoint, no request DTO mapping, no validated tenant/actor-to-employee binding, no approval flow, no provisioning, and no actual Keycloak environment yet.

## Next increment

Implement a thin HTTP controller only after signed-token tests verify issuer, audience, scope, trusted tenant and requester IDs, and negative cross-tenant scenarios. Request creation must use the existing internal service without allowing client-provided identity overrides.
