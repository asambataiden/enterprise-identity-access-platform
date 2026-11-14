# Architecture 04 — Transaction boundaries, recovery and event delivery

**As-built:** Spring Boot modular monolith, PostgreSQL V1–V14, loopback mock target, explicit in-process relay/inbox test bridge. No production broker, scheduler or real provisioning connector.

## 1. Approval / audit / work / outbox atomicity

```mermaid
sequenceDiagram
  participant Caller as Authorized decision boundary
  participant S as ApprovalDecisionService
  participant DB as PostgreSQL
  Caller->>S: APPROVED for PENDING request
  S->>DB: BEGIN; lock request FOR UPDATE
  S->>DB: UPDATE request; INSERT access_decisions
  DB->>DB: V4 trigger: audit evidence
  DB->>DB: V5 trigger: approved work READY
  DB->>DB: V12 trigger: outbox PENDING
  DB-->>S: COMMIT or rollback all
  S-->>Caller: Decision only; not external grant
```

Why: local governance must not commit a decision without its audit and approved-work intent. No remote publish is attempted inside the transaction.

## 2. Lease recovery and uncertain target state

```mermaid
sequenceDiagram
  participant A as Worker A / token A
  participant DB as Work and journal
  participant B as Worker B / token B
  participant Target as Loopback mock target
  A->>DB: Claim and persist STARTED
  A->>Target: POST with stable tenant/request key
  Note over A,Target: A loses ACK or crashes
  Note over DB: Token A expires
  B->>DB: Recover with token B
  B->>Target: GET exact entitlement first
  alt Already present
    Target-->>B: Exact matching tuple
    B->>DB: Record mock VERIFIED
  else Readback unavailable
    Target-->>B: Error/timeout
    B->>DB: Record UNKNOWN; no blind POST
  end
  A->>DB: Late write using token A
  DB-->>A: Stale token rejected
```

Why: local lease fences cannot retract an external side effect. Stable target idempotency and independent readback are both needed. Observation `VERIFIED` refers to the mock target only.

## 3. Outbox relay and inbox duplicate sequence

```mermaid
sequenceDiagram
  participant DB as PostgreSQL
  participant R as Explicit relay
  participant P as In-process publisher
  participant I as Inbox consumer
  R->>DB: Claim event with token / commit
  R->>P: Publish stable event ID
  P->>I: consume(event)
  I->>DB: Atomic receipt + RECEIVED projection
  DB-->>I: Commit
  Note over P,R: ACK lost after consumer committed
  R->>DB: Retry / lease expiry path
  R->>P: Publish same event ID again
  P->>I: consume exact replay
  I->>DB: Find existing receipt; compare payload
  DB-->>I: DUPLICATE; projection unchanged
```

**No exactly-once transport claim.** Duplicate messages can be delivered; the local projection occurs once per event ID within the database. A real broker and real third-party side effect require separate security and idempotency strategies.

## 4. Failure mode matrix

| Failure window | Durable evidence | Intended retry/denial | Test class |
|---|---|---|---|
| Two approvers read PENDING | Locked request, one terminal decision | Second conflicts | `ApprovalDecisionServiceIT` |
| Audit insertion fails | Transaction rollback | No status/decision/work/outbox partial commit | `ApprovalDecisionServiceIT` |
| Lease expires | Work, attempts, current token | New token; stale token rejected | `ProvisioningLeaseServiceIT` |
| Target POST succeeded, ACK lost | STARTED attempt, potentially mock target tuple | Read before write, stable key, mock observation | `MockProvisioningOrchestratorIT` |
| Mock target preflight GET fails | STARTED then UNKNOWN observation | Do not POST blindly | `MockProvisioningOrchestratorIT` |
| Relay publish succeeds but ACK lost | Outbox event and lease | Redelivery, same event ID | `OutboxRelayIT`, `OutboxInboxEndToEndIT` |
| Inbox projection fails | DB transaction rollback | Retry event; no half receipt | `ProvisioningInboxConsumerIT` |
| Same event ID different payload | Existing receipt | Reject conflicting replay | `ProvisioningInboxConsumerIT` |

## 5. Design trade-offs and production gaps

- **`FOR UPDATE`** serializes conflicting decision transitions; **`SKIP LOCKED`** avoids claim contention across independent work rows. Use the appropriate primitive per use case.
- **Fencing** protects local state writes after lease expiry; it is not an external compensating transaction.
- **Read-before-write** reduces duplicate target effects but depends on target read consistency and cannot alone eliminate all races.
- **Outbox** solves loss of local *intent* during a dual-write window; it does not make message delivery exactly once.
- **Inbox** deduplicates one local projection; it does not confer exactly-once effects on remote applications.
- No measured SLO/throughput, broker listener, scheduled worker, production target adapter, backup/restore drill or disaster recovery qualification exists.

## 6. Study order

[Lesson 06: approvals](../learning/06-approval-sod-and-transactional-audit.md) → [07: leasing](../learning/07-provisioning-intent-leases-and-fencing.md) → [08: target reconciliation](../learning/08-adapter-contract-mock-http-reconciliation.md) → [09: quarantine](../learning/09-quarantine-inspection-and-review-evidence.md) → [10: outbox/inbox](../learning/10-transactional-outbox-relay-and-inbox.md). Use the [UC catalogue](../use-cases/README.md) to connect each diagram to business outcomes and negative acceptance cases.
