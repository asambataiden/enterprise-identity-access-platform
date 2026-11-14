# Milestone 06B-1 — Real Keycloak access token through HTTP authentication boundary

**Baseline:** Milestone 06A merged into main at `3832a51`.  
**Scope:** Local opt-in authentication smoke check using the existing synthetic-user Authorization Code + S256 PKCE helper. Does **not** provision a new client, grant permissions, or make a mutating API call.

## Goal

Demonstrate that a **real Keycloak-issued OAuth2 access token** is parsed and accepted by Spring Security's live bearer-token filter, while an anonymous request and a token with a modified signature are rejected. All probes use read-only GET requests to a protected quarantine route.

We intentionally reuse `ops/keycloak/oidc-pkce-smoke.py` rather than creating a second OAuth login process, secret, or token-writing script.

## Architecture and sequence

```mermaid
sequenceDiagram
  actor Operator
  participant Browser
  participant KC as Local Keycloak (8281)
  participant Helper as PKCE + HTTP probe helper
  participant API as Java Resource Server (8082)
  Operator->>Helper: --verify-bearer-boundary
  Helper->>KC: OIDC discovery
  Helper->>Browser: Open Authorization Code + S256 PKCE login
  Browser->>KC: Synthetic-user login
  KC-->>Helper: Authorization code (loopback callback)
  Helper->>KC: Exchange code + verifier
  KC-->>Helper: Signed access_token (memory only)
  Helper->>API: GET quarantine (no bearer)
  API-->>Helper: 401 expected
  Helper->>API: GET quarantine (corrupted signature)
  API-->>Helper: 401 expected
  Helper->>API: GET quarantine (authentic access token)
  API-->>Helper: 403 expected without reviewer authorization, or 200 if authorized
  Helper-->>Operator: HTTP statuses only; no credentials or JWT output
```

## What changed

- `ops/keycloak/bearer-boundary.py` implements three read-only GET probes and validates endpoint origin. It modifies a non-padding base64url character in the signature while preserving the header and payload.
- Existing `ops/keycloak/oidc-pkce-smoke.py` gains `--verify-bearer-boundary`. It acquires the token using existing authorization-code PKCE, runs the probes, and returns **before any POST or business mutation**. It rejects combinations with decision, creation, idempotency, reviewer fixture or mutation options.
- `ops/keycloak/test_bearer_boundary.py` exercises signature corruption, accepted and rejected HTTP statuses, malicious URLs, and the no-token/modified-token/genuine-token ordering.
- Existing hosted Java CI discovers the new `test_*.py` tests under `ops/keycloak`.

## Runbook (local only)

Start the existing Keycloak container and application against the **isolated IAM lab PostgreSQL database**, not any other database. Do not run the application against a shared or production database.

```bash
python3 ops/keycloak/verify-runtime.py
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
```

Use the previous lab runbook to provision a **synthetic user** (and set its tenant identity attribute) before PKCE login. Do not use your real corporate or personal identity. Then, with the Spring Boot IAM application listening on `127.0.0.1:8082`, run:

```bash
python3 ops/keycloak/oidc-pkce-smoke.py \
  --api-url http://127.0.0.1:8082/api/v1/access-requests \
  --scope access:request \
  --verify-bearer-boundary
```

Complete login in the browser with the pre-existing synthetic IAM test user. No tokens, authorization codes, callback URLs, private keys or passwords should be copied into the chat, output or repository.

**Acceptance:** no-token GET -> 401; corrupted-signature GET -> 401; Keycloak-issued token GET -> 403 (no reviewer scope/grant) or 200 (authorized reviewer). The HTTP 403 on the authentic token means the request passed authentication but failed authorization; this is a **security boundary success** for this focused test.

## HTTP and security semantics

**401 Unauthorized:** missing or invalid bearer credentials. Anonymous requests and signature-tampered JWTs must fail authentication before any app-level operation.

**403 Forbidden:** the access token was authenticated but the caller lacks the protected route's scope or local permission. This is deliberately permitted as the successful authentic-token result because the lab user usually has `access:request`, not `provisioning:review`.

**200 OK:** authenticated user was additionally authorized to read the quarantine list; no writes performed.

**Limit:** These probes do *not* prove issuer rejection, audience rejection, expiry rejection, tenant isolation, revocation races, or persistence-side no-write invariants. Those need separate actual JWT negative tokens (e.g. controlled alternative issuer/signing keys or other isolated test realm) and database checks. A signature-mutated token tests signature verification but not all JWT algorithms or key-rotation behavior.

## Troubleshooting

- **Authentic token receives 401:** check issuer and JWKS configuration, trusted signing key, audience mapping, token freshness, the Resource Server runtime and whether the actual token is an *access token*.
- **Anonymous/corrupted request receives 403 or 200:** fail closed; the filter chain or endpoint protection may be misconfigured. Do not loosen expectations.
- **Valid token receives 403:** expected for missing reviewer scope or local reviewer permission. It is not evidence that tenant authorization succeeded.
- **No browser callback:** verify port 8765 availability, authorized loopback redirect URI and synthetic user configuration.
- **Docker orphan container warning:** preserve existing `keycloak-iam-lab-db-1` until its use/data are understood. Do not use `--remove-orphans` as a cleanup shortcut.
- **No test user/password:** use existing local-only synthetic-user provisioning steps and secret prompts. Never paste a token or password into shell arguments, documentation or this chat.

## Evidence and interview learning

Record the Git commit, local Java/Keycloak versions, three HTTP statuses and whether CI passed. Never include actual tokens. Tests are mandatory in CI, but live login remains a local opt-in check; hosted CI does not create a synthetic user or run a browser.

**Why does a 403 for a real token count as progress?** It shows authentication succeeded and authorization was enforced separately. A 401 would indicate token verification failed.

**Why a GET endpoint?** It avoids approval, entitlement, provisioning and database mutation while testing the filter chain.

**Why not trust Python-decoded claims?** The existing Python helper decodes claims for diagnostics only; Spring Security decides token validity by signature, issuer, audience and time validations.

**Next increment — 06B-2/06C:** target signed wrong-issuer, wrong-audience, expired and untrusted-key tokens through the real servlet filter chain; later assert tenant binding and permission effects against PostgreSQL with real issued tokens.
