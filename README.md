# Enterprise Identity & Access Management Platform

## Start learning from scratch

**[Complete Documentation Portal](docs/README.md)** — the main entry point for a properly ordered course: **[13 numbered lessons 00–12](docs/learning/README.md)**, **[UC-001–UC-010 acceptance catalogue](docs/use-cases/README.md)**, **[4 dedicated architecture views](docs/architecture/README.md)**, **[44 historical milestones mapped to lessons](docs/learning/13-implementation-provenance-and-milestone-map.md)**, and the [technical interview workbook](docs/portfolio/technical-interview-workbook.md). Each lesson links actual Java/SQL, diagrams, why/how, tests, failures, limits and Q&A.

**Recommended order:** [Lesson 00](docs/learning/00-orientation-and-prerequisites.md) → [UC-001](docs/use-cases/UC-001-application-access-request.md) → [architecture context](docs/architecture/01-context-containers-and-modules.md) → [Lesson 01 onward](docs/learning/README.md). Historical milestone documents are retained for provenance; this curriculum is the definitive study order.


**Status (2026-11-14):** Educational portfolio release `v0.1.0-portfolio` is published at commit `cf0e2fce324fa6c81bf5bccc4ee49dadf1925391`. Local regression and Java/Security CI passed at that SHA. See the [complete engineering guide](docs/portfolio/complete-engineering-guide.md) for use cases, step-by-step implementation, technology choices, architecture diagrams and interview preparation. This is not production-ready: no real entitlement provisioning, production deployment or production broker; live Keycloak JWT interoperability and tenant-local reviewer authorization were subsequently verified in the local synthetic lab on `main` (Milestones [06C](docs/milestones/06c-live-keycloak-bearer-acceptance.md) and [06D](docs/milestones/06d-live-keycloak-reviewer-authorization.md)); they are **not included in the historical tagged release**.

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
bash ops/demo/run-portfolio-demo.sh # selected tests, no live Keycloak needed
# Optional running API (requires your separately configured local PostgreSQL):
mvn spring-boot:run
# Separate terminal, default port 8080 only when not overridden:
curl -fsS http://127.0.0.1:8080/actuator/health
```

Only `/actuator/health` is intentionally available anonymously. Business endpoints require verified identity and resource authorization; the in-process publisher is **test-only** and exposes no listener. An application run requires a local PostgreSQL URL and credentials; integration tests use disposable PostgreSQL.

## Release gates

See the [scope-frozen portfolio release checklist](docs/release/portfolio-release-gates.md), [demo runbook](docs/release/portfolio-demo-runbook.md), [local operations guide](docs/release/local-operations-runbook.md), [evidence ledger](docs/release/evidence-register.md) and [draft release notes](docs/release/portfolio-release-notes-draft.md) and [offline signed-JWT HTTP tests](docs/milestones/06b2-offline-signed-jwt-http-regression.md). Historical draft PR #49 is obsolete; actual read-only browser PKCE acceptance was completed in merged [PR #68](https://github.com/asambataiden/enterprise-identity-access-platform/pull/68), with reviewer grant/revoke acceptance recorded in merged [PR #69](https://github.com/asambataiden/enterprise-identity-access-platform/pull/69). These results are **post-tag main evidence**, not evidence for immutable `v0.1.0-portfolio`.

## Documentation map

- [Concurrency & Governance Learning Lab: six concurrent retries and opposing approvals](docs/learning/19-concurrency-idempotency-approval-case-studies.md)
- [UC-001 concurrency and approval acceptance addendum](docs/use-cases/UC-001-concurrency-and-approval-addendum.md)

- [Complete engineering guide: use cases, implementation journey, tech stack and architecture diagrams](docs/portfolio/complete-engineering-guide.md)
- [Technical Interview Workbook: source-level questions, model answers and practical exercises](docs/portfolio/technical-interview-workbook.md)

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
