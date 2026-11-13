# Milestone 03D — Assert real PKCE requester identity before self-approval test

**State:** Proposed lab-only test hardening. Live subject-bound SoD acceptance is **not verified**.

The 2026-11-13 operator's first real-token self-approval test produced HTTP 403 after a temporary DECIDE grant, and subsequent guarded PostgreSQL readback proved the SELF request stayed PENDING, with zero decisions, zero audit events and zero remaining requester DECIDE grants. The operator's password-rotation prompt was interrupted, so the browser account actually used was not independently established.

To close that gap, the optional `--assert-self-requester` switch performs a **read-only, fail-closed** check before the HTTP POST. It requires:

- The exact canonical IAM lab tenant, localhost issuer and pre-existing SELF request UUID.
- The token's opaque `sub` to map to the deterministic active requester employee through tenant-scoped `employee_identities`.
- The SELF request to have that employee as `requester_id` and remain PENDING.
- The requester's local `ACCESS_REQUEST_DECIDE` permission to be present at test time.
- Decision mode, `access:decide`, and expected HTTP 403.

The test **does not print tokens or subjects**. Python's parsed claims are not independently cryptographically trusted; the IAM Spring resource server validates the signed token when it processes the request. The guarded SQL fixture only queries the specifically identified local IAM DB container on port 55432. The assertion by itself is a diagnostic precondition, not proof of server-side authorization.

## Operator steps (all within synthetic local lab)

First, follow the existing `03d-self-approval-real-token-lab.md` setup. Confirm the lab IAM API health and that the prior temporary requester grant was revoked.

```zsh
export IAM_LAB_TENANT_ID="8230545d-3560-41e6-af0b-b1ef50778606"
python3 -m py_compile ops/keycloak/oidc-pkce-smoke.py
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
```

If the synthetic requester password is unknown, rotate **only** `iam-lab-test-admin` with `provision-synthetic-user.py --rotate-password`. Complete the hidden password prompt; an interrupted prompt does not indicate rotation succeeded.

**Grant is deliberately opt-in; cleanup is mandatory:**

```zsh
python3 ops/keycloak/self-approval-guard.py --grant

python3 ops/keycloak/oidc-pkce-smoke.py \
  --scope access:decide \
  --decision-request-id feaa03df-c097-5f13-80c4-5b934ac255d5 \
  --decision APPROVED \
  --expect-status 403 \
  --assert-self-requester

# ALWAYS execute, regardless of browser result or timeout:
python3 ops/keycloak/self-approval-guard.py --revoke
```

Log in with **`iam-lab-test-admin` (synthetic requester)** in the IAM lab realm, not the master administrator and not the synthetic approver. A different Keycloak session must fail the identity assertion *before any decision POST*.

Then repeat the read-only SQL check in `03d-self-approval-real-token-lab.md` to prove PENDING, zero decision/audit rows and zero remaining requester grants. If the assertion or cleanup fails, do **not** claim 03D SoD acceptance; recover cleanup and investigate first.

## Architectural learning

The trust layers are independent: Keycloak authenticates the user; PKCE protects the authorization code; the Java resource server validates token signature, issuer and audience; `DecisionActorResolver` binds the authenticated `(iss,sub,tenant_id)` to an active local employee; and `ApprovalDecisionService` enforces permission and no-self-approval. The Python helper's local SQL assertion is only a test-precondition witness. No provisioning or entitlement activation is part of this exercise.

## Operator-observed identity-bound SoD acceptance — 2026-11-13

**Result:** The live signed-token self-approval test **passed the identity, HTTP-denial and independent PostgreSQL persistence/cleanup checks**.

The operator synchronized `main` to PR #26's merge commit `30ce5ffedc1304dbc5547334f9ff626d593037a1`, then ran the guarded test. The terminal output confirms:

1. The canonical requester was temporarily granted `ACCESS_REQUEST_DECIDE` by `self-approval-guard.py --grant`.
2. Real Keycloak authorization-code + S256 PKCE returned an access token with the expected issuer, API audience, `access:decide` scope and tenant claim.
3. `--assert-self-requester` passed its **read-only IAM database check**, showing the token's `(iss,sub,tenant)` mapped to the active employee who requested the PENDING SELF fixture and currently held DECIDE.
4. The authenticated API returned **HTTP 403** to that requester's attempted approval.
5. The shell EXIT trap revoked the temporary DECIDE grant and the operator independently ran a second `--revoke` with successful readback. The separate approver's permissions were not modified by this exercise.

**Why this is meaningful:** The denial cannot be attributed merely to absence of a local DECIDE grant, because the pre-POST assertion verified it was present. Unlike the earlier run, the subject was also checked against the exact requester identity before the POST. The Python helper only decodes claims for diagnostics; the Java resource server is responsible for cryptographic token verification.

**Final acceptance gate — PASSED:** The operator subsequently performed independent, read-only SQL against `keycloak-iam-lab-db-1` and confirmed the SELF request `feaa03df-c097-5f13-80c4-5b934ac255d5` remained `PENDING`, with `decision_rows=0`, `audit_rows=0`, and `requester_decide_grants=0`. These results, together with the previously verified browser principal binding and API HTTP 403, satisfy the **specific real-token self-approval denial acceptance requirement**. Other IAM threats and production security controls remain outside this acceptance scope.

No production target call, provisioning or entitlement activation was performed.
