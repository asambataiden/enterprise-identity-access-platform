# Milestone 04F-2 — Quarantine review evidence, without remediation authority

**Status:** Draft. Requires local Maven verification and hosted CI. No production target or administrative API is enabled.

## Why

04F-1 provides read-only diagnosis of quarantined provisioning. The next operational control is a **durable, structured review record** that can explain why work is being held or escalated, while keeping the work quarantined. A review record must not silently reset attempts, release a lease, grant entitlement or make a production verification claim.

## What

- Flyway **V10** adds `provisioning_quarantine_reviews` scoped by tenant + request and indexed by time; immutable-by-service record identities and foreign keys link to the work item and tenant employee.
- `QuarantineReviewJournal.record(Review)` locks the work item and accepts a review only when its current state is `QUARANTINED` and the supplied reviewer employee belongs to the tenant and is `ACTIVE`.
- The evidence has a caller-provided `reviewId`, reviewer employee UUID, restricted `Decision` (`HOLD`, `ESCALATE`, `RECONCILE_REQUIRED`), and allowlisted `ReasonCode` (`UNCERTAIN_TARGET_STATE`, `POSSIBLE_DUPLICATE`, `POLICY_REVIEW`, `DEPENDENCY_UNAVAILABLE`).
- The same review ID with identical content is an idempotent replay. Reusing it with different content fails closed.
- There are **no free-form exception messages, credentials, or PII descriptions** in this evidence.
- No review action changes `provisioning_work_items`, attempt counts, leases or target entitlements.

## Explicit security caveat

**This is an internal evidence-writing service, not a privileged administrative workflow.** Supplying a UUID does *not* establish the reviewer's authenticated identity, permission, dual authorization or separation of duties. The service validates only tenant membership and an ACTIVE employee row; do not expose it through HTTP or describe the evidence as cryptographically verified authorization. Before any production administrator writes these records, add actor identity from validated security context, least-privilege role checks, tenant authorization, CSRF/abuse controls as appropriate, and an independent approval policy. Database administrators can still alter rows: V10 is **not tamper-proof WORM**.

## State and sequence

```mermaid
sequenceDiagram
    participant Caller as Internal test caller
    participant Journal as QuarantineReviewJournal
    participant DB as PostgreSQL
    Caller->>Journal: record(tenant, request, reviewId, actor, decision, reason)
    Journal->>DB: Lock matching provisioning work item
    DB-->>Journal: QUARANTINED?
    Journal->>DB: Verify actor is an ACTIVE tenant employee
    Journal->>DB: INSERT evidence ON CONFLICT DO NOTHING
    Note over DB: No work-item update; no requeue or target call
    Journal-->>Caller: New record / idempotent replay / denied
```

## Verification

Run `mvn -B --no-transfer-progress clean verify` with disposable PostgreSQL 17 Testcontainers. The integration tests cover quarantine-only recording, exact replay, conflicting review IDs, wrong tenant, missing actor, disabled actor, and unchanged quarantine state and attempts.

## Recovery governance still to build

1. Authenticated administrative identity and *tenant-bound* review permission.
2. Separation of duties and a documented approval policy.
3. Review history, evidence retention, monitoring and tamper-evidence strategy.
4. Independent live target reconciliation before any remediation.
5. Explicit, audited remediation transitions (only after external acceptance gates).

The 03D real Keycloak signed-token approval acceptance test is **still pending**. A local mock `VERIFIED` observation does not prove external effective access.
