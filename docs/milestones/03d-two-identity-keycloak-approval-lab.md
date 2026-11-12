# Milestone 03D — Two-identity Keycloak approval lab

**Status:** scripts and design under review; no live changes performed by GitHub commits.
**Predecessors:** PR #9 real-token CREATE (201/403/404/409) and PR #12 MockMvc approval API + PostgreSQL concurrency tests.

## Why this increment

An injected MockMvc JWT is adequate for controller policy tests, but it does **not** establish that a real browser-authorized, signed Keycloak token has the approval scope or that two different OIDC `sub` values resolve to distinct active employees. We add an isolated and reproducible test fixture rather than claiming an HTTP approval path without evidence.

## Scope and trust

- **Requester** `iam-lab-test-admin`: existing synthetic actor. Its `ACCESS_REQUEST_CREATE` local permission **remains revoked** by default. Existing production or non-IAM Docker services are out of scope.
- **Approver** `iam-lab-test-approver`: a *different* Keycloak account, a distinct deterministic tenant employee, and optionally `ACCESS_REQUEST_DECIDE` only when `--grant` is explicitly used. No default permission grant.
- **Client scope:** `access:decide` is an OPTIONAL client scope on `iam-lab-cli`. Realm import JSON describes new realms; `ensure-decision-scope.py` reconciles only this scope with a running existing realm, verifies readback, refuses incompatible existing configurations.
- **Test requests:** synthetic PENDING records with deterministic IDs and known local tenant request/subject/application. Insertion via a guarded IAM-only SQL fixture is **not evidence** of an authenticated CREATE request. The fixture does not overwrite completed approvals.

These scripts reuse the original strict IAM PostgreSQL container guard: exact container `keycloak-iam-lab-db-1`, published `127.0.0.1:55432`; no arbitrary external DB selection. Lab Keycloak is strictly `http://127.0.0.1:8281`. No secrets or bearer access tokens are printed/saved.

## Operator prerequisites (not run by this PR)

1. Pull the feature branch and run `mvn -B --no-transfer-progress clean verify` and static CI checks.
2. Verify existing IAM Keycloak and IAM-only Postgres are healthy without touching unrelated Docker services.
3. Ensure the IAM Java API uses the current code with **Flyway V3** and the correct isolated PostgreSQL datasource. When port 8082 is already occupied, inspect the process before restarting; do not kill all Java processes.
4. Export `IAM_LAB_TENANT_ID=8230545d-3560-41e6-af0b-b1ef50778606` and restore existing IAM-only bootstrap administrator variables privately. Do not paste credentials, token contents, redirect URLs or callback query strings.
5. Confirm the requester CREATE permission is revoked. **Do not rerun the original CREATE fixture without `--revoke`.**

## Explicit opt-in setup (local only)

```bash
python3 ops/keycloak/ensure-decision-scope.py
python3 ops/keycloak/provision-synthetic-approver.py
# On first creation the script privately prompts for an approver password
# (or uses an existing IAM_LAB_APPROVER_PASSWORD variable; never print it).
# No DECIDE permission is granted above.

python3 ops/keycloak/provision-synthetic-approver.py --grant
python3 ops/keycloak/seed-approval-requests.py
```

The fixture prints three test request IDs. Use the printed **APPROVE** and **REJECT** IDs to run the user-facing browser flow; use a different fresh ID for each outcome. The original user and approver must sign in as the **appropriate distinct synthetic account** when the browser opens.

## Real-token decision test

A. Copy the synthetic UUIDs printed by the request fixture into shell variables (do not include angle-bracket placeholders):

```bash
APPROVE_REQUEST_ID="paste-printed-LAB_03D_APPROVE_REQUEST_ID-here"
REJECT_REQUEST_ID="paste-printed-LAB_03D_REJECT_REQUEST_ID-here"
```

With the **approver** logged into Keycloak:

```bash
python3 ops/keycloak/oidc-pkce-smoke.py \
  --scope access:decide \
  --decision-request-id "$APPROVE_REQUEST_ID" \
  --decision APPROVED \
  --expect-status 200
```

B. With the approver on the separate REJECT request:

```bash
python3 ops/keycloak/oidc-pkce-smoke.py \
  --scope access:decide \
  --decision-request-id "$REJECT_REQUEST_ID" \
  --decision REJECTED \
  --expect-status 200
```

C. Repeat the same approval request to prove `409` with no duplicate decision record; revoke approver permission with `python3 ops/keycloak/provision-synthetic-approver.py --revoke` then submit the unused SELF request as approver and expect `403`. This establishes **permission revocation**, not a real identity-based SoD challenge.

**Important limitation:** To prove self-approval with a real token while isolating the SoD rule, the requester would separately need a valid `access:decide` token **and** a local `ACCESS_REQUEST_DECIDE` grant. This milestone does not grant that permission to the requester, so a 403 by itself cannot establish that it was self-approval (rather than missing local permission). The existing PostgreSQL + MockMvc tests exercise self-approval with the permission present. Distinguish these evidence layers.

D. Once tested, retain only the revocation result for the approver and the already revoked requester CREATE permission. Do not delete local databases or Docker volumes.

## Learning and interview prompts

**Why optional OAuth scope?** A scope restricts which endpoints a token may access. Tenant-specific permission must still be checked against a bound local, active employee. A scope is not an IAM business role.

**Why distinct `sub` values?** Keycloak `sub` denotes a stable principal. The authorization layer binds `(iss,sub,tenant_id)` to employee records. Different employees make separation of duties meaningful.

**Why synthetic SQL requests?** They isolate the **approval decision API** and avoid re-enabling the requester CREATE grant just to create test data. The test must not be misrepresented as end-to-end CREATE→APPROVE.

**What remains?** Live operator outcomes, denial from incorrect/foreign tenants with distinct real principals, full lifecycle tests, separate audit evidence, and downstream provisioning. No entitlement is provisioned by approval.
