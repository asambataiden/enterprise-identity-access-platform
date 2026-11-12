# Milestone 03B — Transactional internal approval service

**Status:** implementation under review; validate local and hosted CI before merging. No new HTTP decision endpoint exists.

## Use case and safety objective

An authorized, active employee who is **not the requester** decides a PENDING same-tenant request, as APPROVED or REJECTED. The result must be atomic: request status and one decision evidence row commit together, or both roll back. **APPROVED never creates an application grant** in this increment.

## Code and data boundaries

- `ApprovalDecisionService.decide(tenantId, approverId, requestId, Decision)` accepts server-provided identity parameters. This is an **internal service**. A future HTTP controller must derive these identifiers from a cryptographically validated JWT + local identity mapping, never directly from a request body.
- `employee_permissions.ACCESS_REQUEST_DECIDE` and active lifecycle status are checked in PostgreSQL for the specific tenant and employee. The repository migration does not seed or grant decision authority.
- The request and its target employee/application/role must exist, belong to the same tenant and be active. Unknown/cross-tenant targets get a generic failure without revealing resource existence.
- `SELECT ... FOR UPDATE OF ar` serializes competing approvers on the access-request row. Status must be `PENDING`; both `APPROVED` and `REJECTED` are terminal states for this narrow workflow.
- The original requester comes from the *locked stored request*, never caller input; Java checks requester != approver, reinforced by the Flyway V3 CHECK/FK constraints.
- `UPDATE access_requests WHERE status='PENDING'` followed by `INSERT access_decisions` executes under one Spring transaction. A failed insert rolls back the status change; unique `(tenant_id, request_id)` prevents duplicate decision records.
- **Retry contract:** another decision, including a repeated identical decision, currently results in a conflict exception. Future HTTP design must decide whether to return 409 or offer explicitly idempotent retrieval; no silent double-approval.
- **Not yet implemented:** HTTP `access:decide` scopes, authenticated approver resolution, audit append-only stream, delegated approval policies, expiry/escalation, provisioning, outbox, external IAM integrations.

## Sequence diagram

```mermaid
sequenceDiagram
    participant A as Authenticated approver boundary (future)
    participant S as ApprovalDecisionService
    participant DB as PostgreSQL
    A->>S: decide(verified tenant, approver, request, decision)
    S->>DB: verify ACTIVE approver + ACCESS_REQUEST_DECIDE
    DB-->>S: valid permission
    S->>DB: SELECT request, active subject/app FOR UPDATE OF request
    DB-->>S: PENDING + persisted requester
    S->>S: reject self-approval; only terminal decision values
    S->>DB: UPDATE PENDING→APPROVED/REJECTED
    S->>DB: INSERT one decision evidence row
    DB-->>S: transaction COMMIT
    Note over DB: No access grant, no downstream provisioning
```

## Verification plan

`ApprovalDecisionServiceIT` runs isolated PostgreSQL 17 Testcontainers and covers:
1. Successful approval with persisted approver and status, and duplicate refusal;
2. Rejection with no grant;
3. Missing/revoked permission;
4. Self-approval even with DECIDE grant;
5. Disabled approver or disabled subject;
6. Foreign tenant / unknown request;
7. Two concurrent opposing decisions: exactly one winner and consistent status/decision.

Run `mvn -B --no-transfer-progress clean verify`. The test DB is disposable and does **not** modify the running IAM lab on port 55432. Do not restore the revoked synthetic CREATE grant for these tests.

## Architecture reasoning / interview practice

**Why a row lock?** Without serialization, two approvers could observe `PENDING` simultaneously, race status updates and create inconsistent decision evidence. PostgreSQL row locking plus transactional updates guarantees a single winning transition; the primary key is an additional guard.

**Why both application checks and DB constraints?** Java provides business intent and useful behavior; PostgreSQL guarantees integrity across concurrent transactions, process restarts and other writers. Neither substitutes for authenticated endpoint authorization.

**Why an internal service first?** It isolates the hard business invariants from transport and OAuth wiring. The next increment must link trusted JWT `iss/sub/tenant_id` to an active approver who holds the explicit local permission. A bearer scope alone is insufficient.

**Why no provisioning after APPROVED?** Approval is a governance decision, not proof that the entitlement exists. Later provisioning must be separately authorized, idempotent, observable, auditable and recoverable.
