# IAM Technical Interview Workbook — v0.1.0-portfolio

**Scope:** source-grounded preparation for Senior Java Backend, Software Architecture, IAM, Cloud/Platform and DevSecOps interviews.  
**Implementation baseline:** annotated tag `v0.1.0-portfolio` → commit `cf0e2fce324fa6c81bf5bccc4ee49dadf1925391`.  
**Companion:** [Complete Engineering Guide](complete-engineering-guide.md).  
**Evidence:** [Final verification register](../release/evidence-register.md).

> This workbook explains **implemented code and tested boundaries**, not an imagined finished IAM product. All scenarios below are synthetic and use the repository's existing tests and PostgreSQL Testcontainers. Real Keycloak-issued token interoperability is not accepted in this release; draft PR #49 remains separate. No external entitlement is granted.

## How to study

For each question, first answer aloud without reading the model answer. Then open the cited Java/SQL/test files, explain **what / why / how / failure / verification / limitation**, and follow the hands-on task. A strong answer refers to the **specific transaction boundary and evidence**, not merely a framework name.

Use this four-part scoring rubric for each question:

| Score | Observable performance |
| --- | --- |
| 0 | Cannot explain the requirement or confuses states/trust boundaries |
| 1 | Names tools and patterns but cannot explain the code or failure path |
| 2 | Explains the implemented code, invariant and corresponding tests accurately |
| 3 | Also articulates concurrency/failure behavior, security trade-offs, limitations and production evolution |

### Mandatory safety

Do not use real employee data, login credentials, long-lived access tokens or actual entitlement targets. Do not run a destructive database/Compose reset. The curated demo runs isolated test containers and requires no Keycloak login.

## Module 1 — Product boundary, architecture and domain vocabulary

### Q01 — What business problem does the capstone solve?

**Model answer:** It demonstrates access governance: a tenant-local employee requests access for a subject, a distinct permitted approver decides, and the system persists auditable intent. The surrounding simulation models the recovery and delivery problems that would arise before a real external grant. Keycloak supplies identity in the local lab; the IAM service enforces business authorization. This release does not activate third-party access.

**Code/evidence:** `AccessRequestService`, `ApprovalDecisionService`, [UC-001](../use-cases/UC-001-application-access-request.md), [capability matrix](../architecture/capability-matrix.md).

**Follow-up:** Which exact persisted fact would independently prove that an external SaaS account has effective access? **Answer:** None in this release; mock `VERIFIED`, `SUCCEEDED` simulation and `RECEIVED` are insufficient.

### Q02 — Why is the design a modular monolith rather than microservices?

**Model answer:** Governance and its initial durable evidence are closely coupled, and Java + PostgreSQL can enforce local atomicity in one process. Premature services would add service-to-service authentication, network failure, deployment and distributed consistency before a measurable scaling/ownership need exists. Domain packages separate responsibilities while retaining one deployment; packages alone do not enforce architectural isolation.

**Code/evidence:** [ADR-0001](../adr/0001-modular-monolith.md), [system overview](../architecture/system-overview.md).  
**Challenge:** Identify a measured reason (independent scale, team ownership, latency or failure isolation) that might justify extracting the relay later.

### Q03 — Explain the difference between authentication, API scope and resource authorization.

**Model answer:** JWT validation establishes trusted identity and token constraints; a Spring Security `SCOPE_access:decide` check gates a route; `DecisionActorResolver` binds `iss/sub/tenant_id` to an active local employee and permission; `ApprovalDecisionService` validates the actual request and separation of duties. All are required. A token with valid signature and scope can legitimately return 403.

**Code/evidence:** `bootstrap/BootstrapSecurityConfiguration.java`, `accessrequest/DecisionActorResolver.java`, `accessrequest/ApprovalDecisionService.java`.  
**Threat exercise:** Explain why a submitted `X-Tenant-Id` header cannot replace server-resolved tenant identity.

### Q04 — Explain `401`, `403`, `404`, and `409` in this project.

**Model answer:** `401` rejects missing/malformed/untrusted bearer authentication; `403` rejects authenticated but under-authorized actions (scope, local actor, permission, SoD); `404` is used for absent/cross-tenant resource hiding; `409` represents incompatible idempotent replay or terminal-decision conflict. A denied response is not sufficient as a security test—assert no durable mutation.

**Code/evidence:** `SignedJwtHttpFilterIT`, `AccessRequestPersistenceIT`, `ApprovalDecisionHttpIT`.  
**Follow-up:** Does `403` prove the token was issued by Keycloak? **No**, not if the test injects a mock JWT principal or test-only decoder.

## Module 2 — Tenant isolation and access-request persistence

### Q05 — Why are composite tenant foreign keys important if service code already checks tenant IDs?

**Model answer:** Java checks provide readable business diagnostics, but concurrency and alternate DB writers can bypass application assumptions. Composite tenant-scoped keys reject cross-tenant references at the authoritative database boundary. Authorization must still verify the requester's actual tenant/permission and resource eligibility; referential integrity is not a substitute for policy.

**Read:** `AccessRequestService.create`, `V1__create_access_request_foundation.sql`, `AccessRequestPersistenceIT`.  
**Lab:** Locate the composite foreign keys and explain what cross-tenant insert they reject.

### Q06 — How does idempotency work when two requests arrive concurrently?

**Model answer:** `AccessRequestService.create` first validates bounded input and eligible tenant-local actor/subject/application/role. Its SQL inserts with `ON CONFLICT (tenant_id,idempotency_key) DO NOTHING`, then reads the row. Equivalent terms return that stored row; changed terms throw `AccessRequestConflictException`. The database unique constraint arbitrates concurrency across JVMs, unlike a Java mutex.

**Code excerpt (representative actual SQL):**

```sql
INSERT INTO access_requests
  (id, tenant_id, requester_id, employee_id, application_id, role_key, idempotency_key)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (tenant_id, idempotency_key) DO NOTHING;
```

**Lab:** In `AccessRequestPersistenceIT`, identify the concurrent retry case, and distinguish “same key, same intent” from “same key, changed intent.”  
**Failure:** Why must a conflict not overwrite the first row?

### Q07 — Why shouldn't the HTTP request body contain requester ID or tenant ID?

**Model answer:** Those values are identity/authorization assertions. If accepted from JSON, an attacker could impersonate another requester or bind a request to a foreign tenant while presenting their own token. The controller must derive the actor from validated JWT claims and local active employee mapping.

**Read:** `AccessRequestController`, `RequestActorResolver`, [01B-2](../milestones/01b-2-authenticated-intake.md).

## Module 3 — Approvals, separation of duties and audit

### Q08 — Explain the approval transaction step by step.

**Model answer:**
1. `ApprovalDecisionService.decide` receives server-resolved tenant/approver/request.
2. It rejects null parameters and verifies the approver is active with `ACCESS_REQUEST_DECIDE`.
3. It loads the tenant-local pending request with `FOR UPDATE OF ar`.
4. It checks requester `!=` approver and status `PENDING`.
5. It changes the status and inserts one `access_decisions` row under `@Transactional`.
6. The V4 trigger inserts matching audit evidence; the V5/V12 path adds approved provisioning and outbox intent only when approved.
7. Failure rolls back local changes; no third-party entitlement is granted.

**Read:** `ApprovalDecisionService.java`, `V3__approval_decision_foundation.sql`, `V4__approval_transactional_audit.sql`, `V5__approved_provisioning_work_items.sql`, `V12__provisioning_transactional_outbox.sql`.

### Q09 — What prevents two approvers from winning the same decision?

**Model answer:** PostgreSQL row locking serializes access to the request; after the first commit, the second transaction sees a terminal status and fails. The one-decision uniqueness constraint is another integrity layer. Java `if` statements alone are insufficient, because two workers can read `PENDING` simultaneously.

**Read/test:** `ApprovalDecisionServiceIT`.  
**Whiteboard:** Draw interleaved T1/T2 and point to the exact blocking SQL lock.

### Q10 — What is separation of duties and how is it enforced?

**Model answer:** Requesters cannot approve their own request. The service compares the approver to the **persisted** requester, not a user-supplied field; DB constraints reinforce the stored relationship. A different approver still requires active status, tenant membership and `ACCESS_REQUEST_DECIDE`. SoD is about the actual transaction, not simply whether a user has both requester and approver permissions.

**Read:** `ApprovalDecisionService`, V3 migration, `ApprovalDecisionHttpIT`.  
**Attack drill:** Attempt to forge a different requester ID in a decision payload; explain why the service never trusts it.

### Q11 — Why persist audit evidence in the same transaction?

**Model answer:** If approval commits but audit fails, the decision can become unauditable. V4's `AFTER INSERT` trigger couples a decision and its audit record to the same PostgreSQL transaction. This is application-level tamper resistance, not immutable/WORM audit storage: a privileged DB administrator can bypass or alter DB controls.

**Read:** [04A](../milestones/04a-transactional-approval-audit.md).  
**Failure lab:** Find the test that forces audit insertion failure and confirms rollback.

## Module 4 — Provisioning intent, leasing and mock target

### Q12 — Why must approval not be treated as effective access?

**Model answer:** `APPROVED` represents a governance decision. `READY` work records execution intent. A third-party system may be unavailable, delayed, inconsistent or explicitly deny the grant. The platform only simulates the target, so it never proves real access.

**Read:** V5, [04B](../milestones/04b-approved-provisioning-intent.md).

### Q13 — How does a provisioning worker obtain exclusive work ownership?

**Model answer:** `ProvisioningLeaseService.claim` validates tenant and lease bounds, quarantines expired final-attempt work, selects the oldest eligible row with `FOR UPDATE SKIP LOCKED`, updates it to `LEASED`, creates a new UUID lease token, sets database-time expiry and increments attempt count in one transaction. Only the current unexpired token holder may renew or record failure. There are at most three provisioning attempts.

**Read:** `ProvisioningLeaseService.java`, V6 migration, `ProvisioningLeaseServiceIT`.  
**Follow-up:** Why use the database clock rather than worker wall-clock time? **Answer:** To avoid cross-worker clock disagreement over lease expiry.

### Q14 — Explain fencing in the stale-worker scenario.

**Model answer:** Worker A owns token A but stalls; its lease expires. Worker B reclaims with token B. A returns and attempts to record completion or failure using token A. The SQL conditional on the **current token and unexpired lease** rejects A's stale update, preventing it from changing B's recovery state.

**Diagram:**

```mermaid
sequenceDiagram
  participant A as Worker A
  participant DB as Work item
  participant B as Worker B
  A->>DB: Claim token A
  Note over A,DB: A stalls; lease A expires
  B->>DB: Recover with token B
  A->>DB: Update using expired token A
  DB-->>A: Rejected
  B->>DB: Update using active token B
  DB-->>B: Allowed when lease remains valid
```

**Caveat:** DB fencing protects local state; a real external target also needs safe/idempotent side-effect semantics.

### Q15 — Why is `STARTED` committed before calling the mock target?

**Model answer:** A process may crash during network interaction. A committed attempt record records that execution *was initiated by the worker*, so recovery can reconcile an uncertain outcome. It does **not** prove a POST reached or changed the target. `ProvisioningAttemptJournal` commits first; `MockProvisioningOrchestrator` is deliberately constructed and is not an automatically scheduled Spring bean.

**Read:** `ProvisioningAttemptJournal.java`, `MockProvisioningOrchestrator.java`, V8 migration.

### Q16 — Why read before write? What if the POST timed out?

**Model answer:** A prior POST may already have applied the entitlement despite a lost acknowledgement. The orchestrator first calls `isEffective`. If the exact tuple exists, it records mock `VERIFIED` without a POST. If absent, it calls `apply` with stable tenant/request idempotency and reads back independently. If the initial read fails, it records `UNKNOWN` and makes **no speculative POST**. The target is localhost-only and remains simulated.

**Read:** `MockProvisioningOrchestrator.java`, `LoopbackHttpProvisioningTargetAdapter.java`, `MockProvisioningOrchestratorIT`.  
**Failure exercise:** Draw a crash after remote apply but before response; explain why readback and a stable key are both required.

### Q17 — How is quarantine different from retry?

**Model answer:** A temporary failure may transition to `RETRY_WAIT` with bounded delay. Reaching the final allowed attempt or expiry results in `QUARANTINED`; the work should not be blindly retried. Read-only inspection reveals error codes/attempts/observations. Internal review records `HOLD`, `ESCALATE`, or `RECONCILE_REQUIRED` without requeueing or granting access.

**Read:** `ProvisioningRecoveryInspector.java`, `QuarantineInspectionController.java`, `QuarantineReviewJournal.java`, V10–V11 migrations.  
**Security check:** An authenticated scope alone must not permit viewing a different tenant's quarantine evidence.

## Module 5 — Reliable events and transaction boundaries

### Q18 — Why use a transactional outbox?

**Model answer:** PostgreSQL commit and a separate broker publish cannot be atomically committed in a normal JDBC transaction. The V12 trigger writes the event **in the same local transaction** as the approved provisioning intent. A later relay can safely attempt delivery without losing the fact that a committed business action needs dispatch.

**Read:** V12, `OutboxDeliveryService.java`, [04G](../milestones/04g-transactional-outbox.md).

### Q19 — What happens if publish succeeds but the relay crashes before acknowledging?

**Model answer:** The event may remain leased and later be retried after expiry; duplicate delivery is possible. `OutboxRelay.runOnce` holds **no DB transaction across publisher.publish**, uses the stable event ID on retries and updates `DELIVERED` only with a valid current lease. An exception may represent uncertainty, not definite non-delivery. Event delivery is at-least-once in the modeled path, not exactly once.

**Read:** `OutboxRelay.java`, `OutboxDeliveryService.java`, [04G-2](../milestones/04g2-outbox-delivery-relay.md).

### Q20 — How does the inbox make repeated events safe?

**Model answer:** `ProvisioningInboxConsumer.consume` inserts a receipt with unique `event_id` and a local `RECEIVED` projection under the **same @Transactional boundary**. An exact replay performs no additional projection; a reused ID with conflicting tenant/request/event/payload is rejected. An exception rolls both writes back. This is **local idempotent projection**, not exactly-once external entitlement execution.

**SQL excerpt (the project uses the same pattern):**

```sql
INSERT INTO provisioning_inbox_receipts
 (event_id, tenant_id, request_id, event_type, schema_version, payload)
VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
ON CONFLICT (event_id) DO NOTHING
RETURNING event_id;
```

**Read:** `ProvisioningInboxConsumer.java`, V14, `ProvisioningInboxConsumerIT`, `OutboxInboxEndToEndIT`.

### Q21 — What does `DELIVERED` or `RECEIVED` actually prove?

**Model answer:** `DELIVERED` is an acknowledgement from the configured publisher contract; `RECEIVED` means the local inbox and projection persisted. Neither proves a production message broker was used, nor that an application granted an entitlement. The in-process publisher is explicitly constructed for tests.

**Read:** `InProcessInboxPublisher.java`, [04G-4](../milestones/04g4-end-to-end-event-flow.md).

## Module 6 — Identity, threat modeling and DevSecOps

### Q22 — How do you distinguish the three JWT test categories?

| Test type | Proves | Does not prove |
|---|---|---|
| `MockMvc.jwt()` principal | HTTP policy and controller behavior | Real cryptographic validation |
| Ephemeral RSA-signed `NimbusJwtDecoder` test / signed HTTP filter test | Signature, issuer, audience, expiry and actual servlet filter rejection using a test-only decoder | Production JWKS auto-configuration or Keycloak-issued token compatibility |
| Live Keycloak PKCE test | Would prove identity-provider/browser/token interoperability **if successfully executed** | Not accepted in tagged release; PR #49 remains blocked |

**Model answer:** I explain precisely which trust boundary each test crosses. We have reproducible signed-token HTTP checks without pretending they are real Keycloak integration. No signing private keys, passwords or real tokens are committed.

**Read:** `SignedJwtTrustValidationTest.java`, `SignedJwtHttpFilterIT.java`, [06B-2](../milestones/06b2-offline-signed-jwt-http-regression.md).

### Q23 — Why did you disable CSRF protection, and when must that decision change?

**Model answer:** The current security chain is stateless, bearer-only, disables Basic and form login, and does not authenticate through automatically attached browser cookies. Under that explicitly scoped model, the current CSRF exception is documented. Introducing cookie/session authentication would require a new CSRF threat-model review and appropriate protection and regression tests.

**Read:** `BootstrapSecurityConfiguration.java`, `config/spotbugs-exclude.xml`.

### Q24 — Why are Gitleaks, Trivy and SpotBugs complementary?

**Model answer:** Gitleaks detects likely secrets in Git; Trivy scans dependencies/filesystem for reported vulnerabilities; SpotBugs + Find Security Bugs analyzes Java bytecode for known defect/security patterns. Each has detector and configuration limitations. The project tracks unresolved analyzer reference names and narrowed exclusions rather than equating green CI with complete security assurance.

**Read:** `.github/workflows/security-ci.yml`, [05B assurance closeout](../milestones/05b4-security-assurance-closeout.md).

### Q25 — Why pin GitHub Actions to full commit SHAs?

**Model answer:** Version tags can be retargeted upstream. Full-SHA pinning reduces the risk that a workflow executes changed upstream action code without a repository change. Our validator covers approved refs in the currently inventoried workflow files; it is not a general-purpose semantic YAML/action security auditor.

**Read:** `config/github-actions-pins.json`, `ops/security/verify_github_actions_pins.py`, `.github/workflows/*.yml`.

### Q26 — What can and can't the CI evidence manifests prove?

**Model answer:** JSON manifests record run identity, test/SAST summaries and SHA-256 report digests without uploading raw potentially sensitive XML. They allow comparing a retained source report to its digest, but they are not signatures, complete provenance attestations or independently re-creatable audit evidence after source artifacts expire. Uploaded curated manifests are retained for 14 days.

**Read:** `ops/security/create_ci_evidence.py`, [05B-3](../milestones/05b3-reproducible-ci-evidence.md).

## Module 7 — Performance, operations and architecture trade-offs

### Q27 — What happens at ten times the workload?

**Model answer:** First measure and define SLOs: HTTP latency/error rate, PostgreSQL pool saturation/lock waits, idempotency-conflict rate, outbox backlog/age, retry/dead-letter counts, and mock-to-real target latency. Then profile SQL and transaction boundaries, use bounded claims and indexes, and size workers to downstream rate limits. The current demo provides no throughput benchmark; neither Kubernetes nor microservice extraction is justified solely by “10x” without evidence.

### Q28 — What is the largest risk in replacing the mock target with SCIM or another real API?

**Model answer:** Identity and entitlement correctness under external uncertainty: scoped credentials, TLS/mTLS, consent/authorization, idempotent write semantics, partial success, eventual consistency, reconciliation, revocation, stale workers, safe retry policy, audit protection and actual effective-state verification. The current contract models some failure cases but has no real provider integration or production operations.

### Q29 — What would you do differently for production readiness?

**Model answer:** A separate acceptance program is required: live IdP contract tests and key rotation; permission revocation races; an authenticated broker/listener; least-privilege DB/service accounts; production TLS and secret rotation; schema/backwards compatibility; real target reconciliation; metrics/alerts/SLOs; backup/restore and failure drills; incident access procedures; targeted security review and penetration testing; a deploy/release/rollback plan. These are **gaps**, not features silently present today.

### Q30 — What is the project's strongest engineering result?

**Model answer:** It integrates tenant-scoped relational constraints, authorization, transactional audit, retry fencing, simulated readback and local idempotent event processing into one reproducible, evidence-gated Java capstone. We verified 16 unit + 105 integration and 55 Python tests at the release SHA, plus passing Java and Security CI. The claims are bounded by what those tests demonstrate.

## Module 8 — Practical exercises and review checklist

### Exercise A — Request replay and tenant isolation

1. Open `AccessRequestService.java` and V1.
2. Identify the idempotency uniqueness constraint and compare-on-replay check.
3. Read `AccessRequestPersistenceIT` and locate the equivalent replay, changed intent, cross-tenant and concurrency scenarios.
4. Explain which layer rejects each case: API actor resolver, service precondition, database constraint or transaction rollback.
5. **Pass:** can name a concrete SQL invariant and test assertion without misrepresenting the test as a live Keycloak login.

### Exercise B — Approval race

1. Trace `ApprovalDecisionController` → `DecisionActorResolver` → `ApprovalDecisionService.decide`.
2. Locate `SELECT ... FOR UPDATE OF ar` and the terminal status check.
3. Locate the V3 one-decision constraint and V4 audit trigger.
4. Draw two concurrent approvers and identify why only one commits.
5. **Pass:** explain rollback scope and why a Java-only self-approval guard would be insufficient.

### Exercise C — Stale lease recovery

1. Open `ProvisioningLeaseService.java`.
2. Explain `SKIP LOCKED`, DB time, lease-token generation and MAX_ATTEMPTS = 3.
3. Construct a whiteboard timeline with token A expiry and token B recovery.
4. Explain why late A cannot renew or fail B's work.
5. **Pass:** distinguish local fencing from exactly-once target side effects.

### Exercise D — Lost target acknowledgement

1. Trace `MockProvisioningOrchestrator.execute`.
2. Show why STARTED commits before mock HTTP.
3. Show why initial readback failure yields UNKNOWN without POST.
4. Explain stable idempotency and post-POST readback.
5. **Pass:** never equate mock VERIFIED with real provisioned access.

### Exercise E — Outbox-to-inbox duplicate

1. Trace V12 → `OutboxRelay` → `InProcessInboxPublisher` → `ProvisioningInboxConsumer`.
2. Draw crash-after-consume / before-delivery-ack.
3. Show how the same event ID is replayed.
4. Find the transaction coupling receipt and projection.
5. **Pass:** explain why the projection count remains one even though delivery can repeat.

### Exercise F — Security evidence and portfolio presentation

1. Compare `SignedJwtHttpFilterIT` to `ApprovalDecisionHttpIT`.
2. Explain which test uses a test decoder vs injected mock principal.
3. Identify all four CI gates and the pinned-action policy.
4. Run the **existing** curated demo and quote the release SHA:
   ```bash
   git rev-parse v0.1.0-portfolio^{commit}
   bash ops/demo/run-portfolio-demo.sh
   ```
5. **Pass:** present exact test evidence and known security gaps without claiming production readiness.

## Module 9 — Three mock interview rounds

### Round I: Senior Java Backend (30–45 minutes)

**Prompt:** “We receive duplicate access requests, an approver races another approver, and the database restarts. Walk me through consistency.”

**Expected answer:** tenant/idempotency unique key; compare stored intent; composite FKs; PostgreSQL row lock; transactional decision/audit/work intent; rollback and Testcontainers evidence. Mention operational gaps and why in-memory synchronization is inadequate.

### Round II: IAM / Security Architecture (30–45 minutes)

**Prompt:** “I have a correctly signed JWT with `access:decide`. Why won't your API approve my chosen request?”

**Expected answer:** trusted JWT → scope → active locally mapped tenant actor → permission → same-tenant target → requester/approver SoD → terminal state. Explain 401 vs 403 and why a 404 can protect foreign resource existence. Clarify that production Keycloak interoperability remains unverified.

### Round III: Platform / DevSecOps (30–45 minutes)

**Prompt:** “A provisioning POST times out and the event relay publishes twice. Is your platform broken?”

**Expected answer:** timeout is uncertain; preflight readback, stable idempotency, write-ahead attempt and lease fencing; outbox at-least-once, inbox receipt/projection transaction, no exactly-once transport. Show CI checks and evidence correlation, then disclose lack of real broker/target, metrics, restore drill and external assessment.

## Module 10 — Test execution, scoring and interview evidence

### Reproducible commands

```bash
git switch main
git pull --ff-only origin main

# Full regression at current checkout commit:
mvn -B --no-transfer-progress clean verify

python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
python3 -m unittest discover -s ops/security -p 'test_*.py'

# Curated test-only demonstration:
bash ops/demo/run-portfolio-demo.sh
```

Do not claim these commands pass for a **new documentation commit** until its CI executes. The published tag remains fixed at `cf0e2fce324fa6c81bf5bccc4ee49dadf1925391`.

### Self-assessment tracker

| Competency | Score (0–3) | Evidence to cite |
|---|---|---|
| Domain/use-case and boundary accuracy | — | UC-001, capability matrix |
| JWT/actor/tenant authorization | — | Security configuration, resolvers, JWT tests |
| PostgreSQL concurrency/idempotency | — | V1/V3, AccessRequestPersistenceIT, ApprovalDecisionServiceIT |
| Transactional audit and intents | — | V4/V5/V12 |
| Leases, fencing and reconciliation | — | V6/V8/V9, mock orchestrator tests |
| Outbox/inbox and duplicate effects | — | V12–V14, OutboxInboxEndToEndIT |
| Security CI and provenance limits | — | Workflows, 05B assurance, evidence register |
| Explaining explicit limitations | — | Capability matrix, release notes |

### Closing interview pitch

> “I built a Java 25/Spring Boot 4.1.2 educational enterprise IAM access-governance platform. It implements tenant-scoped access requests, permission-aware approvals with separation of duties, transactional audit and provisioning intent, recoverable lease/fencing coordination, simulated HTTP target reconciliation and outbox/inbox idempotency. PostgreSQL constraints and Testcontainers validate the durable boundaries. Security CI includes Gitleaks, Trivy and SpotBugs/Find Security Bugs. The tagged release passed 121 Java and 55 Python tests. I distinguish simulated integration and offline JWT evidence from real production IdP interoperability and external entitlement provisioning.”

## Reference map

- [Complete engineering guide](complete-engineering-guide.md)
- [System architecture](../architecture/system-overview.md)
- [UC-001 and acceptance cases](../use-cases/UC-001-application-access-request.md)
- [Release evidence](../release/evidence-register.md)
- [Threat model](../security/threat-model.md)
- [Portfolio demo runbook](../release/portfolio-demo-runbook.md)
- [Security-assurance residual risks](../milestones/05b4-security-assurance-closeout.md)
- [Signed JWT filter test scope](../milestones/06b2-offline-signed-jwt-http-regression.md)

**Completion standard:** You can navigate from an interview question to the actual Java class, SQL migration, integration test and observed evidence, then explain the risk and scope limit without relying on general statements about frameworks.
