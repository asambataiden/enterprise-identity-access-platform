# Milestone 01C-2 — Real Keycloak Authorization Code + PKCE (local lab)

**Status:** implementation committed; operator-run browser login and API token validation **pending**. This is a synthetic local integration exercise, not production-ready authentication or completed IAM governance.

## Goal and constraints

Prove that Keycloak, not a mocked `Jwt` principal, signs an access token consumed by the **real Spring Security resource server**. Never use the password/direct-access grant. Never save, commit, copy or display access/refresh tokens. Keep every service on `127.0.0.1` and do not interfere with payment-platform services on ports 5432/8180 or Mongo Express 8081.

- Keycloak OIDC issuer: `http://127.0.0.1:8281/realms/iam-lab`
- Public synthetic login client: `iam-lab-cli`
- Exact redirect: `http://127.0.0.1:8765/callback`
- PKCE: mandatory S256
- API audience: `iam-governance-api`
- Optional requested scope: `access:request`
- Optional user attribute mapped into the access token: `tenant_id`
- API: `127.0.0.1:8082`; separate disposable IAM PostgreSQL: `127.0.0.1:55432`

The importer creates **no users and no credentials**. Real password/MFA controls and production TLS policy are not implemented in this localhost-only lab.

## 1. Update the feature branch

```bash
git fetch origin
git switch --track origin/feat/iam-keycloak-pkce-browser-lab
python3 ops/keycloak/check-pkce-fixture.py
python3 -m py_compile ops/keycloak/oidc-pkce-smoke.py
```

If the branch already exists locally, switch to it and `git pull --ff-only` instead.

## 2. Re-import Keycloak synthetic realm (IAM lab ONLY)

The existing container imported an **older realm file**. Keycloak does not automatically merge new JSON client settings into an existing realm merely because a mounted file changes. The following `down/up` affects **only** `ops/keycloak/compose.yaml`; it does not stop the payment platform, Mongo Express, Kafka or Capstone 03. Because the IAM Keycloak container uses ephemeral development storage, this **deletes any changes/users in this IAM-only realm**. Review that impact before running.

You need `IAM_LAB_ADMIN_USERNAME` and `IAM_LAB_ADMIN_PASSWORD` set in the current shell (use the existing lab-only credentials). If missing, use **Zsh-compatible** prompts without printing the password:

```zsh
read -r "IAM_LAB_ADMIN_USERNAME?Lab admin username: "
read -rs "IAM_LAB_ADMIN_PASSWORD?Lab admin password: "
printf '\n'
export IAM_LAB_ADMIN_USERNAME IAM_LAB_ADMIN_PASSWORD
```

Then:

```bash
docker compose -f ops/keycloak/compose.yaml down
docker compose -f ops/keycloak/compose.yaml up -d
docker compose -f ops/keycloak/compose.yaml ps
bash ops/keycloak/check-metadata.sh
```

Do not run `docker system prune`, stop other containers or reuse the other project's Keycloak.

## 3. Run an isolated IAM database

Start with a **lab-only temporary password**; do not print or commit it:

```zsh
export IAM_LAB_DB_PASSWORD="$(openssl rand -hex 24)"
export DB_PASSWORD="$IAM_LAB_DB_PASSWORD"
export DB_USERNAME=iam_app
export DB_URL=jdbc:postgresql://127.0.0.1:55432/iamdb

lsof -nP -iTCP:55432 -sTCP:LISTEN || true
docker compose -f ops/keycloak/postgres.compose.yaml up -d
docker compose -f ops/keycloak/postgres.compose.yaml ps
```

This database uses disposable tmpfs storage. **Its data will be lost when the container is removed.** No production/Payment Platform database is touched.

## 4. Create a single synthetic identity

In the [local Keycloak admin console](http://127.0.0.1:8281/admin/), open the `iam-lab` realm. Confirm the `iam-lab-cli` client exists with Standard Flow enabled, Direct Access Grants disabled, Client Authentication disabled, PKCE S256, and redirect URI exactly `http://127.0.0.1:8765/callback`.

Create a **synthetic** user (`iam-lab-test-admin`), enable it, set a lab-only password privately and give it a `tenant_id` user attribute containing a newly generated UUID. Do **not** grant realm administrator privileges. If the user-attribute UI is restricted by a declarative user profile, configure a managed attribute in the local realm; do not weaken production attributes policies. No user data or password goes into Git.

For this first test, the Java database deliberately has **no** binding for that issuer/subject. A valid token with the required audience and scope should therefore reach the authorization resolver and receive **403** (fail closed). The next increment seeds a synthetic local employee identity and verifies HTTP 201.

## 5. Start the Java API using the isolated database

Use the **original terminal from step 3**, where `DB_PASSWORD` is already exported. This avoids copying or displaying the generated database secret:

```zsh
export DB_URL=jdbc:postgresql://127.0.0.1:55432/iamdb
export DB_USERNAME=iam_app
export IAM_JWT_ISSUER_URI=http://127.0.0.1:8281/realms/iam-lab
export IAM_JWT_JWKS_URI=http://127.0.0.1:8281/realms/iam-lab/protocol/openid-connect/certs
export IAM_JWT_AUDIENCE=iam-governance-api

mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

Keep the Java application in the foreground. Run the following Python browser smoke in a **second terminal** after its health endpoint becomes available. The application runs Flyway migrations V1/V2 on the disposable IAM database. Check `curl -fsS http://127.0.0.1:8082/actuator/health`.

## 6. Run the browser PKCE + API smoke

From a terminal with Python 3:

```bash
python3 ops/keycloak/oidc-pkce-smoke.py
```

A browser opens, the **synthetic** user signs in with Keycloak, a one-time authorization code returns to `127.0.0.1:8765`, and the Python tool exchanges it using PKCE. Tokens remain in process memory, never displayed or persisted. The helper prints informational decoded claims (`aud`, scope, tenant presence), then makes one POST to the real Java API, showing only the HTTP status.

**Expected current behavior:** Keycloak code exchange succeeds; the audience and access-request scope appear; the API returns **403** because no identity binding and permission exist in the IAM database. This would demonstrate an authenticated but **unauthorized** caller only if the actual code-exchange and API result are observed. A **401** indicates failure of resource-server token authentication; an unavailable API produces connection failure. A **201** is **not expected** until a safe, verified identity/permission fixture exists.

The Python helper's JWT payload decoding is *informational only*. Cryptographic trust and token validation occur **inside the Java resource server**, not inside that Python decoder.

## Shutdown

Stop Java with Ctrl-C. When finished, remove *only these IAM-lab services*:

```bash
docker compose -f ops/keycloak/postgres.compose.yaml down
docker compose -f ops/keycloak/compose.yaml down
unset IAM_LAB_ADMIN_USERNAME IAM_LAB_ADMIN_PASSWORD IAM_LAB_DB_PASSWORD DB_PASSWORD
```

## Evidence contract

Document exact run commit, lab image, displayed claims flags, observed status and all failures without disclosing bearer tokens, OAuth callback code, credentials or real identities. A 403 after a valid Keycloak login validates fail-closed identity mapping; it is **not** proof of successful authorized access request creation or provisioning.
