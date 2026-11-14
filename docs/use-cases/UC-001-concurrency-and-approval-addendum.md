# UC-001 Addendum — Concurrent Request Intake and Approval Decision

**Type:** executable acceptance-case specification and learning supplement; does not replace original [UC-001](UC-001-application-access-request.md).  
**Baseline:** `v0.1.0-portfolio`; synthetic-data local tests only.  
**Business invariant:** one logical request for an idempotency key, one terminal approval decision per request, no cross-tenant association or self-approval, and audit evidence consistent with committed decision.

## Actors and permissions

- **Requester:** validated bearer principal mapped to an ACTIVE local employee in the tenant with `ACCESS_REQUEST_CREATE`; route requires `SCOPE_access:request`.
- **Subject:** an ACTIVE employee in the same tenant; application and requested role must exist in that tenant.
- **Approver:** distinct ACTIVE tenant employee, mapped via trusted JWT actor and possessing `ACCESS_REQUEST_DECIDE`; decision route requires `SCOPE_access:decide`.
- **DB:** PostgreSQL V1 tenant/composite FKs, uniqueness; V3 approval PK/SoD constraint; V4 transactional audit trigger; V5 approved-work intent; V12 outbox intent.

## Scenario UC-001-C01 — Equivalent retries converge

**Given** the requester is authenticated and locally authorized; the subject, application and role are active and tenant-local; six requests use the same tenant, idempotency key and business terms.

**When** concurrent calls attempt to create the request.

**Then** exactly one logical row exists for the tenant/key, and every successful equivalent retry resolves to its identity. The caller must not create six independently approvable requests.

**Evidence:** `AccessRequestPersistenceIT.parallelEquivalentRetriesProduceOneLogicalRow`; `AccessRequestService.create`; V1 `uq_access_requests_tenant_key`.

## Scenario UC-001-C02 — Changed replay fails without modifying intent

**Given** a request already exists for tenant T / idempotency key K.

**When** a retry uses K but changes requester, subject, application or role.

**Then** the stored intent remains unchanged and `AccessRequestConflictException` is raised; the HTTP contract exposes a conflict response.

**Evidence:** `changedTermsForSameTenantKeyAreRejected` and `differentBusinessTermsWithSameHttpIdempotencyKeyReturnConflict`.

## Scenario UC-001-C03 — Tenant and identity spoofing fails closed

**Given** a caller supplies a valid-looking business payload or spoofed tenant header that points at a different tenant.

**When** actor resolution and tenant-local persistence validation run.

**Then** the request cannot cross tenant ownership and the spoofable header cannot override server-resolved identity. A valid bearer scope alone is insufficient.

**Evidence:** `tenantHeaderCannotOverrideAuthenticatedActor`, `foreignTenantEmployeeCannotBeReferenced`, `mappedActorCannotRequestEmployeeBelongingToAnotherTenantOverHttp`; V1 composite FKs.

## Scenario UC-001-C04 — Opposing approvers race; only one wins

**Given** a `PENDING` access request and two distinct authorized approvers from its tenant.

**When** one chooses APPROVED and the other REJECTED concurrently.

**Then** only one transaction commits a terminal request status and corresponding decision row. The other attempt fails against the updated terminal state. The winning outcome is nondeterministic; the consistency invariant is deterministic.

**Evidence:** `ApprovalDecisionServiceIT.concurrentOpposingDecisionsAllowOnlyOneWinner`, `ApprovalDecisionService.decide`, V3 primary key and SoD constraints, `FOR UPDATE OF ar`.

## Scenario UC-001-C05 — Self-approval forbidden even if scope/permission present

**Given** the original requester has an otherwise valid decision scope and local decision permission.

**When** they attempt to decide their own request.

**Then** the service compares the approver to the **persisted requester** and rejects the decision. The request stays PENDING; no decision/audit event is committed. V3 SoD check reinforces the restriction even for other SQL writers.

**Evidence:** `requesterCannotSelfApproveEvenWithGrant`, `chk_access_decisions_separation_of_duties`, `fk_decision_request_owner`.

## Scenario UC-001-C06 — Audit insertion fails; whole decision rolls back

**Given** a valid decision attempt and an audit write that cannot commit.

**When** inserting `access_decisions` fires the V4 audit trigger and the audit insertion fails.

**Then** the request status change and decision are rolled back together. For approved intent, the dependent provisioning work and outbox event must not survive a failed encompassing transaction.

**Evidence:** `auditInsertionFailureRollsBackRequestAndDecision`, `auditFailureAlsoRollsBackProvisioningIntent`, V4/V5/V12 trigger chain.

## Scenario UC-001-C07 — Rejection must not create provisioning work

**Given** a distinct authorized approver rejects a pending request.

**When** the rejection is committed.

**Then** a terminal `REJECTED` decision and audit evidence exist, but **no approved provisioning work item or event intent** is created. No external entitlement is touched.

**Evidence:** `distinctApproverCanRejectWithoutCreatingGrant`, `rejectedDecisionAndDeniedDecisionNeverEnqueueWork`.

## Non-functional acceptance / negative checks

| ID | Concern | Acceptance |
|---|---|---|
| NFR-01 | Cross-process retry | Uniqueness is enforced by PostgreSQL, not a Java/JVM-local lock |
| NFR-02 | Race and atomicity | Conflicting decisions do not produce diverging request/decision/audit state |
| NFR-03 | No existence disclosure | Foreign-tenant resources are not exposed by response payloads |
| NFR-04 | Safe evidence | No tokens, credentials or employee PII in tests, logs, example fixtures or public artifacts |
| NFR-05 | Reproducibility | Testcontainers PostgreSQL and Flyway V1–V14; run on a recorded commit |
| NFR-06 | Honest security boundary | MockMvc JWT principal tests do not establish real Keycloak interoperability |
| NFR-07 | Honest provisioning boundary | APPROVED, READY, audit/outbox records and mock target observations are not effective external grants |

## Test execution

```bash
# Focused, existing integration classes. No live Keycloak required.
mvn -B --no-transfer-progress \
  -Dit.test=AccessRequestPersistenceIT,ApprovalDecisionServiceIT \
  -DfailIfNoTests=false verify

# Full release regression on the checkout under test:
mvn -B --no-transfer-progress clean verify
```

These are instructions, not a new pass claim for the documentation PR. For immutable prior results see [release evidence](../release/evidence-register.md).

## Implementation trace

| Rule | Service / controller | Migration | Test |
|---|---|---|---|
| Idempotent tenant-local request | `AccessRequestService`; `AccessRequestController` | V1, V2 | `AccessRequestPersistenceIT` |
| Requester identity not client-supplied | `RequestActorResolver` | V2 | `tenantHeaderCannotOverrideAuthenticatedActor` |
| Permission-bound approver | `DecisionActorResolver`; `ApprovalDecisionService` | V2, V3 | `ApprovalDecisionHttpIT` + `ApprovalDecisionServiceIT` |
| One terminal decision | `ApprovalDecisionService` | V3 | `concurrentOpposingDecisionsAllowOnlyOneWinner` |
| SoD from persisted requester | `ApprovalDecisionService` | V3 | `requesterCannotSelfApproveEvenWithGrant` |
| Atomic decision audit | `ApprovalDecisionService` + DB trigger | V4 | `auditInsertionFailureRollsBackRequestAndDecision` |
| Approved-only downstream intent | Trigger chain | V5, V12 | `rejectedDecisionAndDeniedDecisionNeverEnqueueWork` |

## Study questions

1. Would `synchronized` guarantee one logical request if six calls land on three Spring Boot instances? Why not?
2. How can `ON CONFLICT DO NOTHING` produce a valid response without returning an inserted row directly?
3. Why must the existing request payload be compared after a conflicting insert?
4. Why does `FOR UPDATE` address an approval race that a simple SELECT and Java `if` cannot?
5. Which DB constraints enforce SoD even when a second application writer bypasses Java checks?
6. If an audit trigger fails, what must roll back and why?
7. Why might both APPROVED and REJECTED be correct winners in a concurrency test?
8. What does `REJECTED` guarantee about work-item creation?
9. Which test skips real JWT signature verification, and which separate tests exercise signing?
10. What additional evidence would be required before calling the workflow production IAM?

**Related walkthrough:** [Concurrency and Governance Learning Lab](../learning/19-concurrency-idempotency-approval-case-studies.md).
