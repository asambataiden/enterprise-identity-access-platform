# Milestone 01C-2 — Automated synthetic identity provisioning

**Purpose:** Stop configuring synthetic users manually in the Admin Console. Keep declarative client/scope/mappers in `ops/keycloak/realm/iam-lab-realm.json`, and reconcile the one local synthetic user using `ops/keycloak/provision-synthetic-user.py`.

## Local-only security boundaries

- The script only connects to `127.0.0.1:8281`, only targets realm `iam-lab`, and only manages username `iam-lab-test-admin`.
- It authenticates with Keycloak's built-in `admin-cli` using a local **development bootstrap admin**. This uses an admin password grant **only** for local provisioning. Our public `iam-lab-cli` is unchanged and uses Authorization Code + S256 PKCE (never password grants).
- Production automation must instead use a least-privilege scoped Keycloak service account with Client Credentials or private-key JWT, credentials supplied by a secret manager, and TLS.
- Credentials and bearer tokens are held in process memory, not logged, written to Git or passed via command-line flags.
- The script reads existing `users/profile` before adding a declaration for `tenant_id`, and refuses to weaken an existing profile's non-admin permissions.
- An existing user is updated to the requested tenant ID only if needed. Existing password is preserved unless you explicitly add `--rotate-password`.
- No employee identities, application authorization grants, realm-admin roles or permissions are assigned.
- This is a **local lab**, not a production identity lifecycle or user-provisioning architecture.

## Run using macOS Zsh

From the existing IAM repository and feature branch:

```zsh
python3 -m py_compile ops/keycloak/provision-synthetic-user.py
export IAM_LAB_TENANT_ID=8230545d-3560-41e6-af0b-b1ef50778606
```

Use the same terminal that already has the local admin username/password exported. If they are no longer available, enter them privately:

```zsh
read -r "IAM_LAB_ADMIN_USERNAME?Lab admin username: "
read -rs "IAM_LAB_ADMIN_PASSWORD?Lab admin password: "
printf '\n'
export IAM_LAB_ADMIN_USERNAME IAM_LAB_ADMIN_PASSWORD
```

**First run:** create or reconcile the user, then set a unique synthetic-only password using a hidden terminal prompt:

```zsh
python3 ops/keycloak/provision-synthetic-user.py
```

**If a user already exists but its password is wrong:** explicitly reset only this synthetic user's password. This is the appropriate operation for the current login failure:

```zsh
python3 ops/keycloak/provision-synthetic-user.py --rotate-password
```

Enter a new, unique test password of at least 12 characters when prompted. The password is **not printed** and is **not** the Keycloak admin password. The existing admin account and other realms remain unchanged.

**Repeatability:** rerunning without `--rotate-password` verifies the user and tenant configuration without resetting the password. Do not rotate credentials automatically on every app startup.

## Verify authentication

Keep Keycloak on `8281`, the IAM API on `8082`, and the separate IAM database on `55432`. Run from another terminal:

```bash
bash ops/keycloak/check-metadata.sh
python3 ops/keycloak/oidc-pkce-smoke.py
```

Log in as `iam-lab-test-admin` using the synthetic-user password, not the admin password. Complete the browser login promptly.

Expected *if* client mappings are correct: authorization-code exchange succeeds, issuer/audience/scope/tenant claim flags are true, and Java API returns **HTTP 403** because no local employee identity/permission was granted. These must be **observed**, not assumed.

## Caveats

- Recreate only the *disposable IAM Keycloak* container if changing an already-imported realm JSON client definition; normal user resets need **no restart**.
- Do not use `docker compose --remove-orphans`: IAM Keycloak and its lab DB currently share a Compose project name.
- Never print or paste access tokens, admin tokens, passwords, callback URLs containing codes, or database secrets.
- Clear environment variables when finished: `unset IAM_LAB_ADMIN_USERNAME IAM_LAB_ADMIN_PASSWORD IAM_LAB_TEST_USER_PASSWORD IAM_LAB_TENANT_ID`.
- The script reconciles the user profile via Admin API; it does not validate live OIDC token claims or perform positive application authorization. Those require separate tests.

## Evidence

Save non-sensitive provisioning PASS lines, login callback outcome, token claim-presence flags, and returned HTTP code as milestone evidence. Never store credentials or raw JWTs in evidence files.
