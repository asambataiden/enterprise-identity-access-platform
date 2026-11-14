# IAM portfolio release — local operating runbook

**Audience:** developers, reviewers and interview assessors. **Deployment classification:** educational local/test-only. This is *not* a production service operation manual.

## Supported environments and dependencies

- Java 25, Maven 3.9+, Git.
- Docker Desktop or a compatible Docker engine, reachable by Testcontainers.
- Test suite: disposable `postgres:17-alpine` per integration-test class. The tests apply Flyway V1–V14.
- Optional manually started application: a **separate** local PostgreSQL 17 database with `iamdb` and a properly provisioned `iam_app` account; local Keycloak on 127.0.0.1:8281 is necessary only for live OIDC interactions. Never point the demo or tests at real identity or financial data.
- API binds loopback by default (`server.address: 127.0.0.1`). The application YAML defaults to port 8080, but local operators may override it.

## Preferred reproducible demonstration (no long-running services)

```bash
git rev-parse HEAD
bash -n ops/demo/run-portfolio-demo.sh
bash ops/demo/run-portfolio-demo.sh
```

This runs focused tests through disposable Testcontainers PostgreSQL. It requires **neither** starting Keycloak **nor** an IAM API on port 8082. The command is read-only with respect to the operator's existing local IAM lab; it creates and removes its own test containers. The successful demo stages are independently seeded acceptance scenarios, not one persistent HTTP journey.

## Full regression and security validation

```bash
mvn -B --no-transfer-progress clean verify
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
python3 -m unittest discover -s ops/security -p 'test_*.py'
python3 ops/security/verify_github_actions_pins.py
```

CI must prove **Java CI** and **Security CI** (Trivy, Gitleaks, SpotBugs + Find Security Bugs) at the intended release commit. Do not interpret earlier green PR checks as validation of a later commit. Keep the evidence run links and checksums/commit IDs without copying credentials into documentation.

## Optional application process

Only if explicitly testing a running API against a **dedicated non-production database**:

```bash
export DB_URL="jdbc:postgresql://127.0.0.1:55432/iamdb"
export DB_USERNAME="iam_app"
: "${DB_PASSWORD:?Set the password securely in this shell; do not paste it into a report}"
mvn -B --no-transfer-progress spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

The chosen JDBC URL is an **example local laboratory port**, not a guaranteed running database. The service starts only if the given DB, credentials, network port and Flyway schema are valid. Start/stop this Maven process in its own terminal. Verify from another terminal:

```bash
curl --silent --show-error --fail http://127.0.0.1:8082/actuator/health
```

An `UP`/HTTP 200 health response proves application reachability, **not** correct API authentication, authorized provisioning or readiness of any production dependency.

To shut down, use Ctrl+C in the foreground Maven terminal. If a background process already owns 8082, inspect it first:

```bash
lsof -nP -iTCP:8082 -sTCP:LISTEN
```

Stop **only the verified IAM JVM process** when needed. Do not run `killall java`, delete containers, or truncate the database to resolve a port collision.

## Operating constraints and common failures

| Symptom | Interpretation | Safe action |
| --- | --- | --- |
| Docker unavailable | Testcontainers cannot start isolated PostgreSQL | Start Docker Desktop; retry test |
| Flyway validation or connection fails | DB is unavailable, schema mismatched or credentials wrong | Verify database target and configuration; never auto-reset an existing DB |
| API port already used | Another process is listening | Inspect PID/process before restarting |
| Anonymous/malformed JWT returns 401 | Identity not authenticated; expected protected route response | Inspect JWT trust boundaries only if unexpected |
| Valid token without permission returns 403 | Authenticated identity is not authorized | Check scope and tenant-local actor mapping; do **not** bypass controls |
| Keycloak `invalid_grant` / browser login broken | Lab user/admin credentials or auth flow fail | This does not block offline demonstration; leave live interoperability unverified |
| Outbox retries / quarantine | Intended simulated failure behavior | Inspect test assertions and domain state; no live entitlement grant is claimed |
| Unexpected secrets in output | Risk of evidence disclosure | Do not attach raw log; redact locally, rotate any exposed credential as needed |

## Data protection, logs, rollback and cleanup

- Use only generated UUIDs and synthetic test identities. Never import real employee records into the demo.
- Do not print tokens, secrets, authorization callbacks, admin passwords or live customer attributes. Review CI artifact contents and local logs before public sharing.
- Testcontainers manages **its own** disposable PostgreSQL lifecycle. It does not operate on the existing Compose Keycloak data or manually provisioned IAM DB.
- A local Maven `clean` deletes build output in `target/`, **not** database contents. It should not touch user-maintained untracked files.
- The capstone has no validated production database-backup/restore or rollback drill. Flyway migrations are forwards-only in this release; for a production migration, a separately tested backup and rollback plan would be mandatory.
- Do not infer success of external provisioning from `APPROVED`, `DELIVERED`, `RECEIVED`, or a mock target `VERIFIED` observation.

## Responsibility and escalation

This project has no live operator on-call, high availability, production secrets store, automated health-to-alert routing, scheduled outbox relay, or production runbook SLA. If an integration failure arises, stop claiming end-to-end identity lifecycle completion; use the existing deterministic integration tests for scoped investigation. This document is the **portfolio operational guide**, not a substitute for a production operations design.
