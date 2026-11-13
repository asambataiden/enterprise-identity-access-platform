# Enterprise Identity & Access Management Platform

**Status: Milestone 04 completed for educational/local-test scope (through PR #36, `3350df1`); Milestone 05 DevSecOps and operations next. No production broker, scheduler, deployment or real entitlement provisioning.**

A Java 25 / Spring Boot 4.1.2 educational platform for enterprise access governance: access requests, approval controls, tenant isolation, account lifecycle orchestration, evidence and safe provisioning integrations. **No real identities or applications are provisioned in this baseline.**

## Design at a glance

- **Single deployable, modular monolith initially.** Domain-oriented packages, API ↔ application ↔ domain ↔ integration boundaries. Do not split into services prematurely.
- **Keycloak is the external identity provider** for the tested local OIDC lab; JWT resource-server verification and local actor mapping exist in the application. The platform will *not* implement its own credential verification, authorization server, or cryptographic token issuance.
- **Authorization is resource-aware.** Verified JWT authentication and coarse scopes are necessary but not sufficient. A tenant and resource decision, actor/requester separation, and approval constraints are part of UC-001.
- **PostgreSQL + Flyway:** V1–V14 cover tenant isolation, requests, approvals, audit, safe simulation, outbox leasing and idempotent inbox processing. No real external entitlements are provisioned.
- **Security is deny-by-default:** health is anonymous; implemented request, decision and read-only quarantine routes require bearer JWT scopes **and** local actor/permission checks. Other requests are denied. This is not production hardening.

## Architecture

See the [as-built system overview](docs/architecture/system-overview.md) for the current trust boundaries, HTTP routes, event processing sequence and five provisioning responsibility packages. The [capability matrix](docs/architecture/capability-matrix.md) explicitly separates implemented lab/test functions from unavailable production capabilities. The [Milestone 04 evidence closeout](docs/milestones/04-closeout-evidence.md) records observed tests and merged PRs.

The application exposes authenticated access-request creation, approval decisions and **read-only** quarantine inspection. It uses PostgreSQL/Flyway V1–V14 and a deliberately explicit **test-only** outbox-to-inbox bridge. Neither `RECEIVED` projection nor approved work item establishes an actual grant.

## Prerequisites

Java 25, Maven 3.9+, Git. Docker is required for PostgreSQL Testcontainers integration tests. No real user data is used.

```bash
java -version
mvn -version
mvn -B --no-transfer-progress clean verify
mvn spring-boot:run
# separate terminal:
curl -fsS http://127.0.0.1:8080/actuator/health
```

Only `/actuator/health` is intentionally available anonymously. Business endpoints require verified identity and resource authorization; the in-process publisher is **test-only** and exposes no listener. An application run requires a local PostgreSQL URL and credentials; integration tests use disposable PostgreSQL.

## Documentation map

- [UC-001: Request employee access](docs/use-cases/UC-001-application-access-request.md)
- [Milestone 01A: tenant-scoped persistence and idempotency](docs/milestones/01a-persistence-invariants.md)
- [ADR-0001: Modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR-0002: Delegate authentication to Keycloak](docs/adr/0002-identity-provider-boundary.md)
- [As-built architecture](docs/architecture/system-overview.md)
- [Capability matrix](docs/architecture/capability-matrix.md)
- [Milestone 04 closeout and evidence](docs/milestones/04-closeout-evidence.md)
- [Threat model](docs/security/threat-model.md)
- [Delivery roadmap](docs/roadmap.md)
- [Engineering standards](docs/engineering-standards.md)

## GitHub deployment

To create a **public portfolio repository** under your verified `asambataiden` identity (after reviewing all files), run `bash scripts/create-github-repo.sh`. It requires explicit confirmation and rejects an existing remote. This historical setup script is not required for an existing GitHub repository; verify its behavior before using it in any new repository.

## Evidence discipline

Every future acceptance claim requires a reproducible test, observed output, commit SHA and documented scope. No real user attributes, access tokens, client secrets or passwords may enter Git history. This capstone is a portfolio exercise, not an audited IAM product.
