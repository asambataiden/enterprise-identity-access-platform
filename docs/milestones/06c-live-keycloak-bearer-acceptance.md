# Milestone 06C — Real Keycloak access token through the HTTP resource-server boundary

**Status: implementation submitted for review; live browser acceptance still pending.**
This increment replaces the outdated approach in [historical draft PR #49](https://github.com/asambataiden/enterprise-identity-access-platform/pull/49) with a fresh, minimal read-only probe built on protected `main`.

## Why

The IAM release already has RSA-signed test JWTs through Spring Security using a **test-specific decoder**. It also has local Keycloak metadata/JWKS and browser-PKCE helpers, but these do **not** prove that the normal runtime Spring Boot decoder accepts an actual Keycloak access token with correct issuer, audience and signature.

**Acceptance boundary:** With the real Keycloak-issued access token from an Authorization Code + S256 PKCE browser flow, invoke only `GET /api/v1/provisioning/quarantine`. No Java service/SQL changes, no user provisioning or mutating REST calls, no token logging.

## Sequence

```mermaid
sequenceDiagram
  participant O as Operator/browser
  participant K as Local Keycloak :8281
  participant P as PKCE helper :8765
  participant A as IAM Spring Boot :8082
  O->>K: Synthetic-user interactive login (S256)
  K-->>P: Authorization code via loopback callback
  P->>K: Exchange code + PKCE verifier
  K-->>P: Signed access token (memory only)
  P->>A: GET quarantine without Authorization
  A-->>P: 401
  P->>A: GET quarantine with signature-tampered token
  A-->>P: 401
  P->>A: GET quarantine with genuine Keycloak token
  A-->>P: 403 when missing scope/grant; 200 if fully authorized
  P-->>O: Only HTTP status codes and categorical PASS/FAIL
```

## What the responses actually establish

- **401 for anonymous:** the route requires authentication.
- **401 for a signature-tampered JWT:** the real API rejects a changed signature.
- **403 for a genuine JWT** (with `access:request` scope): proves the token passed the HTTP authentication stage but **not** the `provisioning:review` route or local active-actor authorization.
- **200 for a genuine JWT** (with reviewer scope and correctly provisioned synthetic DB identity/permission): exercises the actual authenticated, tenant-local read-only route. Do not claim this until observed.
- Neither result proves real Keycloak user lifecycle integration or external entitlement provisioning.

No test succeeds if the genuine token returns 401, any redirect, or an unexpected response. The helper refuses redirects and only contacts port 8082 on localhost/127.0.0.1 for this API probe.

## Preparation — controlled developer lab

Prerequisites are Java 25, Maven, Docker, the isolated IAM PostgreSQL local test database, and the existing Keycloak 26.8.2 Compose configuration. **Do not run against a shared database, public bind address or real identity.** Keep your Keycloak lab credentials in local, non-committed environment variables or secret prompts and never paste credentials/JWTs/callback authorization codes into chat or GitHub.

1. Ensure you have an isolated, disposable IAM PostgreSQL endpoint configured through the existing `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` variables. This must be a distinct application database from the Keycloak container's own storage.
2. Confirm your synthetic Keycloak user and `iam-lab-cli` browser PKCE client exist using the existing synthetic-user runbooks. Imported realm configuration alone does not create usable credentials or a mapped tenant employee.
3. Start the Keycloak local lab using existing [Compose file](../../ops/keycloak/compose.yaml). The container is bound to `127.0.0.1:8281` and requires `IAM_LAB_ADMIN_USERNAME` / `IAM_LAB_ADMIN_PASSWORD`; never hard-code these into a committed Compose override.
4. Start the IAM Spring Boot API on port **8082** bound to **127.0.0.1**, with the **normal JWT decoder**, not the offline test decoder. Keep `spring.profiles.active` free of `openapi-local`; Swagger exposure is irrelevant here.
5. Verify runtime Keycloak metadata and the two Python test suites before attempting browser login.

```bash
python3 ops/keycloak/verify-runtime.py
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
python3 -m unittest discover -s ops/security -p 'test_*.py'
# Separately, with the API running on 127.0.0.1:8082:
curl -s -o /dev/null -w '%{http_code}\\n' \
  http://127.0.0.1:8082/api/v1/provisioning/quarantine
# Expected unauthenticated response: 401
```

### Run browser PKCE + read-only bearer boundary verification

From the root of the current feature branch, with your synthetic Keycloak login ready:

```bash
python3 ops/keycloak/oidc-pkce-smoke.py \
  --api-url http://127.0.0.1:8082/api/v1/access-requests \
  --scope access:request \
  --verify-bearer-boundary
```

Complete the authentication in your browser. The helper obtains the access token **in memory**, checks OIDC metadata and diagnostic token fields, then performs three **read-only** GETs. No POST is made to the IAM API in this mode.

**Minimum 06C acceptance:** `401 / 401 / 403` (or `401 / 401 / 200` if already authorized) from real API calls. Save redacted HTTP status output, git SHA, Keycloak/Java versions, the precise synthetic fixture class and successful CI links. Do not capture browser callbacks or tokens.

### Additional 06D authorization acceptance (separate exercise)

Once the minimum JWT-authentication evidence passes, use the dedicated synthetic quarantine reviewer fixture and `provisioning:review` optional Keycloak scope to obtain a genuine `200` and deliberately revoke the local review permission to obtain `403`. Existing helpers include `provision-synthetic-quarantine-reviewer.py`, `ensure-quarantine-review-scope.py` and `seed-authorized-iam-fixture.py`. Review their exact effects and preserve the isolated fixture database; do not run their provisioning/grant changes as part of this **read-only** increment.

**Important:** the read-only probe itself does not check local reviewer identity state or perform a before/after DB no-write comparison; those need separate fixture-scoped assertions. An observed 403 does not independently demonstrate tenant isolation, permission mapping or successful scope assertion.

## Regression, threat controls and failure modes

The existing Java CI automatically discovers `ops/keycloak/test_*.py`. The added tests check signature tampering, three-probe ordering, local URL validation, redirect refusal, accepted status sets, rejected status sets and credential redaction by not emitting token values.

- **Genuine token gets 401:** Do not weaken the security configuration. Check normal resource-server issuer/JWKS and audience validation, token expiry, and whether a real access token (not an ID token) was supplied. Do not share token contents.
- **Tampered JWT gets 403/200:** Security regression; reject the attempt.
- **Genuine token gets 403:** Appropriate for the default `access:request` scope on a `provisioning:review` route. This is not a local-permission PASS.
- **No callback:** Verify loopback port 8765, browser authorization redirect URI, test-user state, and the PKCE client's realm configuration. Do not bypass PKCE or use password grant.
- **Accidental non-loopback API endpoint:** The probe raises `ValueError` before any HTTP calls. Do not weaken host validation or allow redirects carrying Authorization.
- **Outdated draft PR #49:** Do not merge or cherry-pick as-is; this new branch is based on current protected main and intentionally excludes stale historic tests/architecture assumptions.

## Interview questions

**Q: Why is a real Keycloak JWT different from the offline RSA-signed test JWT?** The actual Keycloak process selects signing keys and returns a token through the browser OAuth2 Authorization Code flow; Spring Security validates it using the configured runtime issuer/JWKS. Offline signed tests inject a test-specific decoder and cannot establish external IdP compatibility.

**Q: Why 403 can indicate successful authentication but not full authorization?** The JWT is accepted by the authentication stage but lacks the route scope or active local authorization; the business use case is still denied.

**Q: Why a GET-only probe?** It safely isolates the authentication/authorization boundary before attempting any mutation or durable provisioning.

**Q: What is still excluded?** Real Keycloak consent/role admin policy, token refresh/revocation semantics, production access grants, issuer-key rotation under load, external entitlements, human identity production integration, and production readiness.

Related: [offline signed JWT filter tests](06b2-offline-signed-jwt-http-regression.md), [IAM OpenAPI boundary](../api/README.md), [Keycloak OIDC lesson](../learning/05-keycloak-oidc-pkce-local-lab.md) and [capability matrix](../architecture/capability-matrix.md).
