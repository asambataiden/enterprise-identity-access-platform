# IAM Milestone 04 — Provisioning package responsibility refactor

## Intent
Reduce a flat 16-class Java production package into cohesive responsibility-based
packages without changing runtime behavior, SQL, API routes, JSON schemas, security
configuration, or database migration checksums.

## Package map

| Package | Responsibility |
| --- | --- |
| `provisioning.execution` | Lease management, orchestration, simulated execution, attempt journal |
| `provisioning.target` | Target port, loopback adapter, mock observation journal |
| `provisioning.outbox` | Durable delivery, relay, publisher port, in-process test bridge |
| `provisioning.inbox` | Transactional idempotent consumer and RECEIVED projection |
| `provisioning.quarantine` | Recovery inspection, quarantine review, authorization-facing HTTP adapter |

## Boundaries
- The modular monolith and all Spring components stay under `dev.asambataiden.iam`.
- The in-process bridge remains explicitly invoked. No scheduler or production broker is added.
- The target adapter is a simulation; no real entitlement is granted.
- Existing migration versions V1–V14 are unchanged.
- Names are intentionally stable to minimize risk and preserve source history.
- Package moves require updating both main and test Java imports and package declarations.

## Validation gate
1. `git diff --check` and inspect `git diff --stat` for renames.
2. `mvn -B --no-transfer-progress clean verify` (all unit and integration tests).
3. `python3 -m unittest discover -s ops/keycloak -p 'test_*.py'`.
4. Review GitHub Java CI after opening the PR. Record observed counts and commit SHA.

## Interview rationale
Packages reflect cohesive responsibilities and minimize cognitive load without
introducing artificial microservices or dependencies. The primary tradeoff is more
explicit imports. A later architecture review should evaluate package visibility,
ports, cross-package dependencies, and enforcing module boundaries with ArchUnit.
