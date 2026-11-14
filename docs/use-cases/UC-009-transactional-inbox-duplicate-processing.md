# UC-009 — Transactional inbox deduplication and local projection

**As-built acceptance scenario · educational portfolio scope.** This describes verified implementation/test intent from the existing repository; it is not a new production capability. [Use-case index](README.md) · [Learning lesson](../learning/10-transactional-outbox-relay-and-inbox.md).

## Business outcome and actors

**Actors/components:** Explicit in-process test publisher, `ProvisioningInboxConsumer`, PostgreSQL receipts and projections.

**Trigger:** The same event ID can arrive more than once after an outbox relay loses an acknowledgement.

**Preconditions:** Trusted internal envelope has eventId, tenantId, requestId, eventType PROVISIONING_REQUESTED, schemaVersion=1 and nonblank JSON payload. This is not an authenticated production broker listener.

**Successful result:** First receipt and RECEIVED projection insert commit together. Exact repeated event returns DUPLICATE without a second projection. Conflicting replay is rejected.

## Main path — Given / When / Then

**Given** valid preconditions and a correctly scoped internal or HTTP caller, **when** this workflow is invoked, **then** First receipt and RECEIVED projection insert commit together. Exact repeated event returns DUPLICATE without a second projection. Conflicting replay is rejected.

### Step-by-step implementation reasoning

1. Establish the trust boundary and input that must never be accepted as unverified authority.
2. Trace the Java entry point and distinguish HTTP-authenticated controllers from internal-only or explicit test collaborators.
3. Trace the persistence rules/migration and identify the transaction/lease/event identity that makes a retry safe.
4. Inspect the existing tests, including at least one rejection, race or rollback scenario.
5. Distinguish the recorded test evidence and mocked actors from any live IdP/broker/third-party claim.

## Negative acceptance and recovery

1. **UC-009-N01** — Same event ID, exact same payload → DUPLICATE no new projection.
2. **UC-009-N02** — Same event ID with changed tenant/request/type/version/JSON payload → error, no changed business effect.
3. **UC-009-N03** — Projection insertion fails → receipt rolls back in the same transaction.
4. **UC-009-N04** — Missing/invalid envelope → fail closed.
5. **UC-009-N05** — Consumer unavailable before commit → no receipt/projection; relay can retry.

## Architectural flow

```mermaid
sequenceDiagram
  participant P as Internal test publisher
  participant I as Inbox consumer
  participant DB as PostgreSQL
  P->>I: eventId, tenant, request, payload
  I->>DB: BEGIN; INSERT receipt ON CONFLICT DO NOTHING
  alt Fresh event
    I->>DB: INSERT RECEIVED projection
    DB-->>I: COMMIT APPLIED
  else Exact replay
    I->>DB: Compare stored envelope
    DB-->>I: DUPLICATE no new projection
  else Conflicting replay
    I-->>P: Reject and roll back
  end
```

Diagram shows implemented contract or controlled test flow; it does not imply a deployed production service or external entitlement.

## Invariants and trade-offs

- Unique eventId receipt is the deduplication point, separate from original access-request idempotency key.
- Receipt and projection share a DB transaction; this is local idempotent effect, not exactly-once message delivery.
- RECEIVED does not assert effective access or successful real entitlement.

## Traceability: code, data, tests

**Source**
- [`src/main/java/dev/asambataiden/iam/provisioning/inbox/ProvisioningInboxConsumer.java`](../../src/main/java/dev/asambataiden/iam/provisioning/inbox/ProvisioningInboxConsumer.java)
- [`src/main/java/dev/asambataiden/iam/provisioning/outbox/InProcessInboxPublisher.java`](../../src/main/java/dev/asambataiden/iam/provisioning/outbox/InProcessInboxPublisher.java)

**Database / related persistence**
- [`src/main/resources/db/migration/V14__transactional_provisioning_inbox.sql`](../../src/main/resources/db/migration/V14__transactional_provisioning_inbox.sql)

**Existing executable tests and validators**
- [`src/test/java/dev/asambataiden/iam/provisioning/inbox/ProvisioningInboxConsumerIT.java`](../../src/test/java/dev/asambataiden/iam/provisioning/inbox/ProvisioningInboxConsumerIT.java)
- [`src/test/java/dev/asambataiden/iam/provisioning/outbox/OutboxInboxEndToEndIT.java`](../../src/test/java/dev/asambataiden/iam/provisioning/outbox/OutboxInboxEndToEndIT.java)

## Review, reproduction and interview questions

- Run only the relevant existing tests or validators from this list; record the exact current commit, command and result. For the entire Java suite: `mvn -B --no-transfer-progress clean verify` with Docker.
- Explain which failure could occur **after** a database commit or outside an authenticated boundary.
- Identify the invariant that should survive a crash or retry; explain how to confirm no unauthorized state change.
- Name one production gap and its acceptance criterion before designing a real integration.

**Evidence/limits:** release results are tied to `v0.1.0-portfolio` commit `cf0e2fce324fa6c81bf5bccc4ee49dadf1925391`; documentation commits do not retroactively change those tests. There is no verified live Keycloak bearer interoperability, production broker or third-party entitlement provisioning.