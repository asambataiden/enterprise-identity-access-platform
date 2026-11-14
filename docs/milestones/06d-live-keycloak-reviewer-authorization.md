# Milestone 06D — Real Keycloak tenant-local reviewer authorization

**Status:** all three live HTTP authorization scenarios were reported successful by the operator on **2026-11-14** against a locally running Spring Boot IAM API on `127.0.0.1:8082` and Keycloak realm `iam-lab` at `127.0.0.1:8281`. This records supplied terminal evidence, **not** independent execution in hosted CI. No token, password, subject, or authorization-code values have been retained.

**Implementation baseline:** `main` after PR #68 merge, `e731910` (06C read-only PKCE bearer acceptance). A separate `git rev-parse HEAD` was not captured with the 06D runs; the exact binary commit running on port 8082 was not independently attested.

## User journey and security boundary

1. The dedicated synthetic Keycloak principal `iam-lab-test-quarantine-reviewer` completes the browser **Authorization Code + S256 PKCE** flow, with optional `provisioning:review` scope.
2. Spring Security's normal runtime Resource Server validates the real bearer token and requires `SCOPE_provisioning:review` on the read-only `GET /api/v1/provisioning/quarantine` route.
3. `QuarantineReviewActorResolver` resolves verified issuer, opaque subject and `tenant_id` against the active tenant-local `employee_identities` / `employees` mapping, then requires `PROVISIONING_QUARANTINE_REVIEW` in `employee_permissions`.
4. The same synthetic principal is denied when the permission is absent, allowed when present, and denied again after revocation.

## Operator-observed acceptance matrix

| Scenario | Keycloak PKCE | Diagnostic identity readback | Local grant | Real API status | Result |
| --- | --- | --- | --- | --- | --- |
| Reviewer without permission | PASS | Exact synthetic active reviewer mapping PASS | Absent | **403** | PASS |
| Reviewer with permission granted | PASS | Exact synthetic active reviewer mapping PASS | Present | **200**; quarantine records: **0** | PASS |
| Same reviewer after grant revocation | PASS | Exact synthetic active reviewer mapping PASS | Absent | **403** | PASS |

The operator also reported that the preexisting original requester's `ACCESS_REQUEST_CREATE` permission remained revoked during the reviewer provisioning/grant/revoke operations. The `insufficient_scope` bearer challenge diagnostic was `False` in both observed 403 cases, consistent with denial in local application authorization rather than missing OAuth scope. HTTP 200 returned an empty quarantine list; this exercise does **not** validate nonempty quarantine item listing or cross-tenant records.

### Commands used — synthetic, isolated, explicit

Initial no-grant setup:

```bash
export IAM_LAB_TENANT_ID="8230545d-3560-41e6-af0b-b1ef50778606"
python3 ops/keycloak/ensure-quarantine-review-scope.py
python3 ops/keycloak/provision-synthetic-quarantine-reviewer.py
```

Run the **same** read-only HTTP probe before and after permission removal:

```bash
python3 ops/keycloak/oidc-pkce-smoke.py \
  --api-url http://127.0.0.1:8082/api/v1/access-requests \
  --scope provisioning:review \
  --quarantine-list \
  --expect-status 403 \
  --assert-quarantine-reviewer
```

Grant and assert authorized read:

```bash
python3 ops/keycloak/provision-synthetic-quarantine-reviewer.py --grant
python3 ops/keycloak/oidc-pkce-smoke.py \
  --api-url http://127.0.0.1:8082/api/v1/access-requests \
  --scope provisioning:review \
  --quarantine-list \
  --expect-status 200 \
  --assert-quarantine-reviewer
```

Revoke and repeat denied-read probe:

```bash
python3 ops/keycloak/provision-synthetic-quarantine-reviewer.py --revoke
# Rerun the no-grant --expect-status 403 probe above.
```

**End state:** reviewer remains provisioned; tenant-local `PROVISIONING_QUARANTINE_REVIEW` permission was confirmed **absent** after revocation.

## Isolated lab recovery observations

Before acceptance, the synthetic reviewer had an existing database identity row whose `subject` no longer matched its current Keycloak identity. The reported count-only diagnostic was: employee exists `1`, reviewer issuer mapping `1`, mapping matches current Keycloak subject `0`, subject bound elsewhere `0`, reviewer grant `0`. Subsequent provisioning output verified that the correct current identity was bound; the operator did not supply the exact recovery SQL output, so **do not assert which mutation corrected the stale mapping**.

A separate Keycloak `master` admin OAuth request returned `invalid_grant`: the operator found that the locally exported admin **username** differed from the running container bootstrap username, while the password matched. Restoring the local environment's username allowed the existing optional-scope helper to run successfully. Neither issue required relaxing application security or resetting the lab.

## Threat model, tests, and limits

- Actual Keycloak-issued JWTs were used for all three positive/negative authorization scenarios. Python decoded claims are **diagnostics only**; Java/Spring Security performs token verification.
- The endpoint is **GET-only**. No approval decision, entitlement provisioning, quarantine decision, or state mutation was triggered by the acceptance probes. Permission-grant and revoke are explicitly scoped synthetic lab setup operations.
- Evidence supports valid JWT + scope + tenant-local active reviewer permission enforcement. It does not prove multi-tenant cross-access resistance with real tokens, external target provisioning, token revocation propagation, production IdP hardening, or full distributed deployment.
- Reusing an existing token across a grant/revoke transition without a new login, persistence-side no-write checks, and separate token lifecycle/revocation tests were not demonstrated.
- Do not place real logins, credentials, JWTs, callback URLs, bootstrap passwords, or user subject identifiers in a README, CI artifact or pull request.

## Interview explanation

**Why is valid JWT authentication insufficient?** Authentication proves token identity and cryptographic validity. Authorization requires correct route scope and active tenant-local permissions. The observed `403 → 200 → 403` transition demonstrates explicit application authorization independent of merely holding a cryptographically valid token.

**Why test revocation?** A successful 200 alone could hide stale grants. Denial after removing the local grant establishes that the resource server consults the current tenant-local permission state for these requests.

**What does HTTP 200 with zero items prove?** Authorized access to the list endpoint, not correct filtering for nonempty/foreign tenant data.

**Release interpretation:** Milestone 06D is completed as a *local synthetic educational demonstration*, not a production identity platform certification. The historical `v0.1.0-portfolio` tag predates this evidence. Complete final CI/release gates before publishing a new portfolio tag.

Related: [06C real JWT authentication exercise](06c-live-keycloak-bearer-acceptance.md), [capability matrix](../architecture/capability-matrix.md), [release evidence register](../release/evidence-register.md).
