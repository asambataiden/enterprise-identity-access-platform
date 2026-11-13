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
