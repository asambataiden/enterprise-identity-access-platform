# Milestone 04F-1 — Read-only operational recovery inspection

**Status:** Draft; local Maven and exact-head CI gates pending.

## Why

After three failed leases a provisioning work item is quarantined. An operator must understand *what happened* before any action could requeue or otherwise alter the request. `APPROVED`, `STARTED`, `VERIFIED` on a mock target, and real effective entitlement are different facts.

## What

`ProvisioningRecoveryInspector` provides internal read-only `inspect(tenantId, requestId)` and a bounded `quarantined(tenantId, limit)` method (1–100). Each snapshot includes work state, attempt count, allowlisted error code, next-attempt/lease-expiry timestamps, number of durable STARTED attempts and counts of mock VERIFIED / ABSENT / UNKNOWN observations.

- Tenant and request IDs are mandatory; no cross-tenant fallback.
- The quarantine list is ordered by creation time and request ID, and bounded by 100.
- Only `SELECT` statements are executed; no mutation of leases, attempts, observations or entitlements.
- The service is an internal Spring bean, **not an HTTP administration endpoint**. No new operator role is implied.
- Mock VERIFIED counts are mock evidence; they do not prove an external production grant.
- No Flyway migration is necessary, because V6–V9 already contain the required columns and evidence tables.

## Recovery runbook (manual decision framework — no automatic release)

1. Identify the tenant and approved request; independently verify the correct tenant context and authorized operator.
2. Inspect state, attempt count, error code, durable STARTED evidence and per-attempt mock observations.
3. If outcomes are UNKNOWN or STARTED without readback, **reconcile the actual target before considering any retry**. A prior operation might have succeeded despite a lost acknowledgement.
4. If there is evidence of an unexpected or mismatched entitlement, halt and investigate. Do not blindly reapply.
5. Keep quarantined work quarantined until an independently reviewed, audited procedure authorizes any further action. There is **no requeue or reset operation in this milestone**.

## Validation

Run `mvn -B --no-transfer-progress clean verify` using disposable PostgreSQL Testcontainers. Tests cover terminal quarantine snapshots, STARTED counts, tenant isolation, bounded list size and absence of state mutation.

## Limitations

An internal service method does not by itself provide authorization or security policy enforcement: any future administrative API must enforce privileged identity, tenant authorization, audit trails, rate limits and read-only DB grants. The current inspector issues one snapshot query per listed item (N+1), intentionally acceptable only for small bounded diagnostic lists; future scaling should use a set-based query.

Outstanding: real Keycloak signed-token approval acceptance (03D), deprecation warning in the loopback adapter, authenticated external target implementation, tamper-resistant evidence storage and reviewed admin remediation.
