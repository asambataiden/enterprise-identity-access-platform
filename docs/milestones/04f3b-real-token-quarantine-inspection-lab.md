# Milestone 04F-3B — Local Keycloak quarantine inspection acceptance lab

**Status:** Draft fixture. No live realm, identity, permission or work-item changes are performed by the GitHub PR. The operator must opt in separately.

## Goal

Verify that the **read-only** 04F-3A quarantine GET API requires BOTH a real Keycloak access token with the optional `provisioning:review` scope AND the current tenant-bound `PROVISIONING_QUARANTINE_REVIEW` employee grant. Then revoke the grant and expect HTTP 403 with a fresh browser PKCE login.

## Fixed boundaries

- Keycloak realm `iam-lab` on `127.0.0.1:8281`, public PKCE client `iam-lab-cli`.
- IAM-only PostgreSQL container `keycloak-iam-lab-db-1` on `127.0.0.1:55432` with Flyway V11; no payment database.
- Canonical tenant `8230545d-3560-41e6-af0b-b1ef50778606`.
- Dedicated reviewer account `iam-lab-test-quarantine-reviewer` mapped to its own deterministic employee UUID, distinct from the synthetic requester and approver.
- `ensure-quarantine-review-scope.py` changes only the optional scope on the designated synthetic PKCE client. It does not alter existing default scopes.
- `provision-synthetic-quarantine-reviewer.py` provisions/validates only this reviewer, with NO grant by default. The `--grant` and `--revoke` flags affect only `PROVISIONING_QUARANTINE_REVIEW` on its employee. Revoke cannot create a missing reviewer.
- `oidc-pkce-smoke.py --quarantine-list` makes **GET** `/api/v1/provisioning/quarantine`, validates that a successful response is a list containing only `QUARANTINED` snapshots, and never sends a decision, a lease transition or a provisioning request.
- Never log or paste passwords, bearer tokens, OAuth codes or callback URLs. Python only decodes access token claims diagnostically. The Java resource server validates signatures.

## Verification — before touching local lab

```zsh
git fetch origin
git switch --track origin/feat/iam-04f3b-reviewer-live-lab
python3 -m py_compile ops/keycloak/ensure-quarantine-review-scope.py \
  ops/keycloak/provision-synthetic-quarantine-reviewer.py ops/keycloak/oidc-pkce-smoke.py
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
```

## Opt-in local setup

Ensure the IAM API is actually running the **new V11/04F-3A code**, not an older process on port 8082. Confirm `curl -fsS http://127.0.0.1:8082/actuator/health`, Keycloak metadata and the isolated database's current Flyway version. Do not restart unrelated containers or Java processes.

Restore existing `IAM_LAB_ADMIN_USERNAME` and `IAM_LAB_ADMIN_PASSWORD` privately in this shell; these refer to the **master realm bootstrap administrator**, NOT a synthetic browser user. Then:

```zsh
export IAM_LAB_TENANT_ID="8230545d-3560-41e6-af0b-b1ef50778606"
python3 ops/keycloak/ensure-quarantine-review-scope.py
python3 ops/keycloak/provision-synthetic-quarantine-reviewer.py
```

On initial account creation, enter a private reviewer-only password (12+ characters). Do not grant the permission until ready for the browser acceptance exercise.

## Positive and negative authorization tests

The grant is intentional and temporary. Run this sequence from a separate local terminal with access to the IAM API:

```zsh
(
  trap 'python3 ops/keycloak/provision-synthetic-quarantine-reviewer.py --revoke' EXIT
  python3 ops/keycloak/provision-synthetic-quarantine-reviewer.py --grant || exit 1
  python3 ops/keycloak/oidc-pkce-smoke.py \
    --scope provisioning:review \
    --quarantine-list --expect-status 200
)
```

Sign in as `iam-lab-test-quarantine-reviewer` in a fresh/private browser session. A successful HTTP 200 plus a list of QUARANTINED snapshots (possibly empty) shows the authorized read boundary.

The EXIT trap is best-effort and does not survive machine power failure. If it does not confirm a successful revoke, manually rerun `--revoke` until the guard confirms the grant is absent.

**After revocation**, run a second fresh browser login as that same reviewer:

```zsh
python3 ops/keycloak/oidc-pkce-smoke.py \
  --scope provisioning:review \
  --quarantine-list --expect-status 403
```

An HTTP 403 despite a `provisioning:review` token scope demonstrates live local-permission revocation. Optionally check the application rejects a different signed-in principal or a token missing the scope; these remain separate evidence gates.

## Evidence limitations

An empty GET list is sufficient to prove **authorized access to the collection endpoint**, not the correctness of returning a specific quarantined work item. A separate, disposable and fully audited quarantine-state fixture is needed to prove **nonempty response details**, explicit cross-tenant isolation with two real identities, and GET idempotence under concurrency. No production target reconciliation, remediation, review-write API or entitlement provisioning is authorized here.

The reviewer UUID and permission grant must not be supplied by the caller as authoritative HTTP headers. The server resolves the identity from the signed token and PostgreSQL authorization table on every call.
