# Milestone 04F-3C — Quarantine Reviewer Subject Binding

Date: 2026-11-13
Status: Local acceptance passed; broader quarantine acceptance pending.

## Objective

Establish explicit subject-to-employee identity binding for
the dedicated synthetic quarantine reviewer during real
Keycloak Authorization Code + PKCE acceptance.

## Implementation

- Extended the existing OIDC PKCE smoke helper.
- Added --assert-quarantine-reviewer.
- Validated canonical tenant and issuer.
- Checked the opaque Keycloak subject against the dedicated
  active reviewer identity mapping.
- Checked the expected local review-permission state.
- Preserved read-only API operations and token secrecy.

## Observed validation

- Offline Python unit tests: 33/33 passed.
- IAM API health: UP.
- IAM Keycloak discovery: reachable.
- Isolated IAM PostgreSQL: healthy.
- Real Keycloak PKCE: PASS.
- Reviewer subject binding: PASS.
- Authorized quarantine GET: HTTP 200, 0 records.
- Automatic permission revocation: PASS.
- Revoked reviewer GET: HTTP 403.
- Original requester CREATE grant: absent.

## Security boundary

The Python helper decodes JWT claims for local diagnostic
checks and does not independently verify token signatures.

The Spring Security resource server performs cryptographic
JWT validation and application authorization.

No production identities or unrelated permissions were
intentionally modified.

## Outstanding acceptance

- Nonempty quarantine collection and detail response.
- Real signed-token cross-tenant isolation.
- Before/after persistence-state verification.
- Read-only and concurrency acceptance evidence.
