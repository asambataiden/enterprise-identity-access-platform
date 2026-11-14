# UC-001 — Tenant administrator requests application access for an employee

**Historical specification (Milestone 00):** this page records the *initial proposed* UC-001 contract and contains future candidate states/objects. At the published `v0.1.0-portfolio` release, the scoped request intake, authorization policy, tenant-safe persistence, approvals and mock reliability workflows have code/tests. Consult the [as-built use-case catalogue](README.md), [UC-001 concurrency addendum](UC-001-concurrency-and-approval-addendum.md) and [capability matrix](../architecture/capability-matrix.md) before interpreting any original “not implemented” statements. Real target entitlements and production IAM remain absent.

**Original priority:** first business vertical slice.

## Business outcome

An authorized tenant administrator submits an application access request for an employee in the *same* organization. A separate authorized approver makes a decision. An approved request may later produce an application grant through an idempotent provisioner; a rejected request must never create a grant.

## Actors

- Requester: tenant administrator with access-request permission; may not approve own request.
- Subject: employee with a tenant-local identity.
- Approver: another authenticated actor with authority for this application and tenant.
- Auditor: read-only access to permitted audit evidence, no ability to grant rights.
- Identity provider: Keycloak for signed tokens, not the business entitlement source of truth.

## Preconditions and trust boundaries

- Application/tenant/employee are internal identifiers; **never trust tenant identity supplied only in the JSON body**.
- Actor's tenant identity and authorities derive from a validated access token and explicit server-side mapping.
- Only roles/entitlements recognized by the application policy may be requested; never accept arbitrary administrator scope from a caller.
- User lifecycle state and application eligibility must be validated before any grant.

## Minimal request state model

`PENDING -> APPROVED | REJECTED | CANCELLED`; `APPROVED -> PROVISIONING -> ACTIVE | PROVISIONING_FAILED` are **future candidate states**, not implemented behavior. Enumerate and document legal transitions before coding. Revocation will have its own workflow.

## Acceptance contract — write tests before implementation

| ID | Input / condition | Expected invariant |
| --- | --- | --- |
| A01 | Valid tenant admin requests permitted role for same-tenant employee | One request created with `PENDING` and actor/subject/tenant recorded |
| A02 | No JWT, malformed or expired token | Unauthorized; no write |
| A03 | Wrong scope or role | Forbidden; no write |
| A04 | Employee belongs to another tenant | Denied, no existence disclosure, no write |
| A05 | Admin attempts self-approval | Denied; request remains pending |
| A06 | Approver belongs to another tenant | Denied; no decision or grant |
| A07 | Approver rejects | `REJECTED`, no active grant or provisioning event |
| A08 | Retry with same tenant/request-id and equivalent terms | Same logical request, not two entries |
| A09 | Same idempotency key with changed terms | Conflict; previous request unchanged |
| A10 | Two approvals race | Single legal transition, one auditable decision |
| A11 | Subject disabled before approval/provisioning | Fail closed; no active grant |
| A12 | Request payload has unrecognized role or malformed IDs | Validation error; no write |

## Persistent model — next milestone design candidate

`tenants`, `employees`, `applications`, `application_roles`, `access_requests`, `access_decisions`, `access_grants`, `audit_events`; unique tenant-scoped idempotency constraint, ownership-enforcing keys, optimistic locking/row-level concurrency, and outbox where a provisioner crosses a transaction boundary. This is **design only** until Flyway migrations and tests exist.

## Testing strategy

Spring Security negative HTTP tests + PostgreSQL Testcontainers for isolation, concurrency, state transitions and outbox atomicity. Use threat-model review for authorization changes; explicitly test both role-level and object-level denial.

## Interview prompt

Explain why a valid OAuth2 access token cannot by itself authorize approval of a specific employee's requested role, and how self-approval and cross-tenant access are prevented.
