# Milestone 04G-3 — Transactional inbox and idempotent projection

## Why
An at-least-once publisher can deliver an event twice if its acknowledgement is lost. A consumer must avoid repeating a business effect after redelivery.

## What
- Flyway V14 adds `provisioning_inbox_receipts` keyed by immutable `event_id` and `provisioning_event_projections` keyed by tenant/request.
- Explicit `ProvisioningInboxConsumer.consume(Message)` commits receipt and the mock **RECEIVED** projection in one PostgreSQL transaction. A database exception rolls both back.
- Exact replay is a no-op; conflicting event identities or payloads fail closed.
- PostgreSQL composite foreign key keeps the projection bound to its tenant's receipt.
- No real target entitlement, broker listener, scheduler, or security permission is enabled.

## Flow
```mermaid
sequenceDiagram
  participant P as Publisher (test)
  participant C as Inbox consumer
  participant D as PostgreSQL
  P->>C: Deliver stable eventId + envelope
  C->>D: BEGIN; INSERT inbox receipt ON CONFLICT DO NOTHING
  alt first delivery
    C->>D: INSERT projection RECEIVED
    D-->>C: COMMIT
  else exact replay
    C->>D: Compare stored identity and JSONB payload
    D-->>C: COMMIT without another projection
  else conflicting replay
    C-->>P: Reject and roll back
  end
```

## Why this is not exactly-once transport
Duplicates can still be delivered. The simulated business effect is applied at most once within a single PostgreSQL database because receipt and effect are atomic. External side effects would require their own stable idempotency and readback policy.

## Boundaries and next work
- The input is a **trusted internal envelope contract** in this lab; there is no externally exposed API. Real brokers require signature/trust, tenant authorization and schema validation before invoking the consumer.
- The projection records an event as received, **not effective access**. Do not infer successful provisioning from this projection.
- Event retention, operator replay authorization, broker listener, poison-event handling, tracing, and performance benchmarks are future capabilities.
- Historical events are never automatically backfilled.

## Tests
Run `mvn -B --no-transfer-progress clean verify` with PostgreSQL Testcontainers, then the Python tests. Record observed results rather than predicting success. Focused test `-Dit.test=ProvisioningInboxConsumerIT` covers delivery, duplicate, conflict, malformed envelope, rollback, and concurrency.

## Interview questions
1. Why can't an outbox alone prevent duplicate business effects?
2. What must be written in the same transaction as an inbox deduplication marker?
3. How do you handle an eventId reused with a changed payload or tenant?
4. What are the limits of exactly-once business effects when a consumer calls a remote API?
