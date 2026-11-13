# Milestone 04F-3A — Authenticated, read-only quarantine inspection

**Status:** Draft; local Maven verification and exact-head CI required before merge. Deliberately excludes review-writing and remediation.

## Why

Milestones 04F-1/2 gave internal inspection and an evidence journal but no authenticated administration. The real Keycloak 03D approval acceptance is now complete. Reviewers need a **distinct privilege boundary**: approval permission is not quarantine review permission, and an OAuth scope is not an application business grant.

## What

- Flyway **V11** extends the existing employee-permission allowlist with `PROVISIONING_QUARANTINE_REVIEW`. No employees are automatically granted it.
- The bearer-only security filter allows `GET /api/v1/provisioning/quarantine` and `GET /api/v1/provisioning/quarantine/{requestId}` only with `SCOPE_provisioning:review`. All other verbs on these paths remain default-denied.
- `QuarantineReviewActorResolver` derives `tenant_id` from the validated JWT, resolves exact `(tenant, issuer, subject)` to a currently ACTIVE local employee, and demands the dedicated local permission. Caller-supplied `X-Tenant-Id`, URL and JSON cannot select the tenant.
- `QuarantineInspectionController` lists at most 100 quarantined snapshots and returns only quarantined detail. A missing/unavailable request yields 404. No other tenant's request is queried.
- Database-backed MockMvc tests cover scope/permission enforcement, revocation, disabled employees, incorrect tenant claims, unknown/unquarantined requests, bounded list and unchanged state.

## Threat model and guardrails

**There is no new write endpoint.** `QuarantineReviewJournal` remains internal. GET cannot write decisions, renew leases, retry quarantines, contact mock/production targets or alter entitlements. No automatic data migration creates permissions for users.

An IAM operator must later explicitly grant `PROVISIONING_QUARANTINE_REVIEW` in an authorized, audited workflow. OAuth client configuration must separately supply the `provisioning:review` scope. This PR does not modify a running Keycloak realm, grant a role or claim that a real-token quarantine API acceptance has been tested.

In this lab, application-level authorization checks are performed against the current PostgreSQL state on each call; they must not depend only on a potentially stale OAuth token claim. JWT validation is the resource server's responsibility. No PII, secrets or raw tokens should be added to logs or responses.

## Acceptance and follow-up

Run `mvn -B --no-transfer-progress clean verify` with isolated PostgreSQL 17 Testcontainers and require exact-head CI success. After code acceptance, add an **explicitly authorized, independent test-only lab reviewer** and conduct a live Keycloak scope + tenant grant/revocation acceptance check before describing the API as real-token verified.

The next increment **04F-3B** can introduce authenticated review writes, but only after independent validation of reviewer authorization, separation-of-duties policy, idempotent review identity and an audit model. Remediation/requeue remains out of scope.
