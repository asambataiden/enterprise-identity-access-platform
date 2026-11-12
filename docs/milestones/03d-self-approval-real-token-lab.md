# Milestone 03D — Real-token self-approval (separation of duties) lab

**Status:** scripts and instructions proposed; live security acceptance **not yet executed**. Intended only for the existing localhost IAM lab.

## What and why

Positive real-Keycloak decisions and post-revocation denial were previously observed. The post-revocation HTTP 403 alone does **not** prove the self-approval rule: the operator had no local DECIDE permission. Here we temporarily grant DECIDE to the **same synthetic employee who requested** the still-PENDING SELF fixture, acquire that requester's **own** real signed Keycloak token using authorization-code + S256 PKCE, and expect HTTP 403 specifically because requester and approver are identical.

The application checks the local permission before the self-approval prohibition. Therefore, successful pre-grant readback, the correct logged-in principal, and unchanged PENDING request with no decision/audit row are all required evidence.

## Boundaries

- Fixture: tenant `8230545d-3560-41e6-af0b-b1ef50778606` only; the exact isolated DB container and loopback 55432 binding are enforced by `seed-authorized-iam-fixture.py`.
- Requester account: `iam-lab-test-admin` in the `iam-lab` realm (not the master-realm bootstrap administrator).
- Only `ACCESS_REQUEST_DECIDE` for this **requester** is temporarily inserted and later deleted. CREATE remains revoked. Separate synthetic approver's DECIDE remains revoked.
- Helper does not change passwords or Keycloak users, redirect URIs, client scopes, containers, other databases or target entitlements.
- The script's `--grant` refuses non-PENDING SELF requests, missing active requester identity binding, active CREATE permission or an existing separate-approver DECIDE permission.
- `--revoke` always attempts cleanup and does not require the request still to be PENDING.
- **Never** paste passwords, access tokens, OAuth code or callback URLs. Do not use this script outside the isolated lab.

## Runbook — Mac / zsh

From the updated feature branch, confirm IAM API at 8082, Keycloak at 8281 and IAM-only PostgreSQL healthy.

```zsh
export IAM_LAB_TENANT_ID="8230545d-3560-41e6-af0b-b1ef50778606"
python3 -m py_compile ops/keycloak/self-approval-guard.py
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
```

If the password for the **requester** is unknown, rotate **only** that synthetic account first, with existing locally configured master administrator credentials, and choose a private lab password (at least 12 characters):

```zsh
python3 ops/keycloak/provision-synthetic-user.py --rotate-password
```

This operation is optional; do not rotate the separate approver's password.

### Explicit grant and mandatory cleanup

Execute these only within a controlled session. The grant is a deliberate temporary change to the IAM lab database:

```zsh
python3 ops/keycloak/self-approval-guard.py --grant
```

Expected output includes `PASS: synthetic requester DECIDE grant present` and the deterministic `LAB_03D_SELF_REQUEST_ID`. Save that UUID; it is not a credential.

In a **private browser window**, sign in using **`iam-lab-test-admin` in the `iam-lab` realm**, never the bootstrap master admin and never the separate approver:

```zsh
python3 ops/keycloak/oidc-pkce-smoke.py \
  --scope access:decide \
  --decision-request-id feaa03df-c097-5f13-80c4-5b934ac255d5 \
  --decision APPROVED \
  --expect-status 403
```

The helper should print real PKCE exchange success, expected audience/scope/tenant claims and HTTP 403. This is **not sufficient by itself** if the wrong account was logged in.

**Immediately after the run (whether success, error, or timeout):**

```zsh
python3 ops/keycloak/self-approval-guard.py --revoke
```

Confirm `PASS: synthetic requester DECIDE grant revoked`. If the test hangs, interrupt the script and still perform cleanup. If you lose the terminal, rerun `--revoke` from a new shell with `IAM_LAB_TENANT_ID` set. Never leave the temporary grant enabled.

### Independent evidence after cleanup

Run a read-only status and audit query in the verified lab database:

```zsh
docker exec -i keycloak-iam-lab-db-1 sh -c '
exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 <<SQL
SELECT ar.id, ar.status,
       (SELECT count(*) FROM access_decisions d
          WHERE d.tenant_id=ar.tenant_id AND d.request_id=ar.id) AS decision_rows,
       (SELECT count(*) FROM approval_audit_events a
          WHERE a.tenant_id=ar.tenant_id AND a.request_id=ar.id) AS audit_rows,
       (SELECT count(*) FROM employee_permissions p
          WHERE p.tenant_id=ar.tenant_id AND p.employee_id=ar.requester_id
            AND p.permission_key='''ACCESS_REQUEST_DECIDE''') AS requester_decide_grants
FROM access_requests ar
WHERE ar.id='''feaa03df-c097-5f13-80c4-5b934ac255d5''';
SQL
'
```

Expected `PENDING`, zero decision rows, zero audit rows and zero requester DECIDE grants after cleanup.

## Interpretation and remaining caveat

The evidence proves self-approval denied in the API with a signed Keycloak token **only if** the browser used the requester identity and the local DECIDE grant was confirmed active during the attempt. It does not prove production-grade tenant isolation, MFA, cryptographic verification inside the Python helper, or entitlement provisioning. The Java resource server validates the trusted JWT; Python only decodes claims for diagnostics.

After acceptance, revoke remains mandatory. Do not add a privileged quarantine review endpoint as a side effect of this lab.
