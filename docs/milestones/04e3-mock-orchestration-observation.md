# Milestone 04E-3 — Mock-only provisioning orchestration and readback evidence

**Status:** Draft; local and GitHub CI verification required. No real target adapter is registered or invoked.

## What and why

The V8 attempt journal and localhost-only HTTP adapter previously existed independently. 04E-3 joins them in one explicitly constructed, **non-Spring-managed** orchestrator while distinguishing remote mock observation from actual entitlement provisioning success.

## Execution sequence

```mermaid
sequenceDiagram
    participant T as Test
    participant J as Attempt Journal
    participant DB as PostgreSQL
    participant M as Local Mock HTTP Target
    participant O as Observation Journal
    T->>J: start(valid lease)
    J->>DB: Lock and validate lease / INSERT STARTED
    DB-->>T: Commit STARTED
    T->>M: POST mock entitlement, stable idempotency key
    Note over T,M: HTTP acknowledgement may be lost or delayed
    T->>M: GET independent readback
    M-->>T: Exact tuple / absent / error
    T->>O: record(VERIFIED | ABSENT | UNKNOWN)
    O->>DB: Lock active lease + validate persisted STARTED / INSERT observation
    DB-->>T: Commit observation
    Note over DB: Work item remains LEASED; no SUCCEEDED transition
```

## Core guarantees

- `ProvisioningAttemptJournal.start` finishes its own Spring transaction **before** the mock HTTP request. A committed STARTED record is a prerequisite.
- `MockProvisioningOrchestrator` is constructed explicitly with `LoopbackHttpProvisioningTargetAdapter` and is **not** registered as a Spring bean or scheduled worker. It cannot silently bind to a real target.
- The adapter uses a stable key scoped to tenant + request, so lease retry does not create a different target idempotency identity.
- An apply exception leaves the outcome **uncertain**; independent GET readback still runs.
- `VERIFIED` means the exact entitlement tuple was observed on the **mock HTTP target only**. `ABSENT` means GET reported no matching tuple. `UNKNOWN` means readback failed or timed out. These are not production success states.
- V9 `provisioning_mock_observations` records one observation per attempt. The writer locks the work item and requires the **current unexpired lease token**, matching attempt and persisted STARTED record. Exact replay is safe, conflicting outcomes reject.
- No `SUCCEEDED` or simulated entitlement state changes are made. The work item remains LEASED so that a separate policy can later decide whether to release, retry or reconcile.

## Tests and recovery

Integration tests cover observation without STARTED (denied), exact replay, conflicting outcome, tenant and token fencing after lease recovery, complete mock HTTP POST/GET, unavailable target -> UNKNOWN, and unchanged work-item status. Existing mock HTTP timeout/redirect tests and PostgreSQL migration tests must remain green.

Run `mvn -B --no-transfer-progress clean verify` with disposable Testcontainers databases.

## Operational limits / subsequent work

- A single outcome per attempt is a deliberate simplification. Late readback changes and manual reconciliation require an append-only event model, which must be designed separately.
- A lost lease after remote apply can leave only STARTED evidence. Next claim uses the same stable idempotency key, and reconciliation must read back before any further change.
- No production HTTP connector, workers, admin API, secrets, automated leasing schedule, external entitlement activation or external completion transitions.
- Privileged SQL operators can alter mock evidence; no claim of WORM storage.
- Do not deploy V9 to a persistent IAM lab database without reviewing existing data and backup.
- **03D Keycloak real signed-token approval acceptance is still pending.**

## Interview framing

Describe the difference between **write-ahead attempt**, **external apply**, **readback evidence**, and **verified effective access**. Database transactions cannot atomically commit a remote HTTP side effect; idempotency and reconciliation bridge that gap without falsely claiming exactly-once delivery.
