# Historical portfolio release candidate notes — enterprise IAM governance platform

**Historical status at writing:** DRAFT release candidate based on `main` `4a2cb72`, 2026-11-14. **Current publication status:** the educational portfolio was subsequently verified and tagged as `v0.1.0-portfolio` at `cf0e2fce324fa6c81bf5bccc4ee49dadf1925391`. See the [final evidence addendum](evidence-register.md) and merged PR #53. This document preserves the original draft wording for provenance and should not be presented as an outstanding pre-publication gate. **Production approval remains absent.**

## Project summary

This Java 25 / Spring Boot 4.1.2 / PostgreSQL 17 modular monolith demonstrates **enterprise access-governance engineering under realistic transactional and security constraints**, using synthetic identities and locally simulated integration boundaries. Keycloak is the intended identity provider; it is **not** replaced by a custom password or token service.

## Included capabilities

- Tenant-scoped access requests, immutable intent/idempotency behavior, actor mapping and authorization checks.
- Approval/rejection rules, separation of duties, local permission checks and transactional decision audit.
- Controlled simulated provisioning attempts, lease expiration/recovery, loopback mock-target readback and quarantine inspection.
- Transactional outbox, lease-fenced delivery, explicitly invoked in-process relay, transactional inbox/deduplicated projection.
- Spring Security bearer-token HTTP boundary with JWT signature/issuer/audience/expiry negative regression using test-only signing keys and decoder configuration.
- Testcontainers PostgreSQL and Flyway V1–V14 migration exercises.
- CI with Java verification, Gitleaks, Trivy, SpotBugs/Find Security Bugs and pinned GitHub Actions dependencies.
- One-command repeatable portfolio walkthrough: `bash ops/demo/run-portfolio-demo.sh`.

## Deliberately excluded or unverified

- **No live third-party entitlements are provisioned**; a mock `VERIFIED` or inbox `RECEIVED` is not a real grant.
- Live Keycloak Authorization Code + PKCE token acceptance is **unverified** after local test-user/admin login failure (draft PR #49).
- No production SCIM connector, message broker/listener, background scheduler, Kubernetes or multi-cloud deployment.
- No proven production-grade secrets, TLS/HA, complete monitoring/SLO implementation, disaster recovery drill or security certification.
- The curated demo uses multiple isolated fixtures, not a single externally deployed user-journey test.

## Historical verification plan (final results now recorded)

See [evidence register](evidence-register.md) for **both** the originally prepared PR #50–#52 evidence and the later **observed final release-head regression/CI/tag**. Never relabel older PR evidence as if it tested another SHA.

## Usage and learning resources

- [README](../../README.md)
- [Architecture](../architecture/system-overview.md)
- [Capability matrix](../architecture/capability-matrix.md)
- [Threat model](../security/threat-model.md)
- [Demo](portfolio-demo-runbook.md)
- [Operations](local-operations-runbook.md)
- [Acceptance checklist](portfolio-release-gates.md)

## Interview articulation

The strongest design decision is **separating identity authentication from local resource authorization** and **separating approval intent from effective external access**. Database constraints, scoped actor resolution, transactional event persistence and deduplicated local processing provide meaningful evidence of safety. The project is successful as a verifiable educational portfolio even when a local identity provider login fails—provided that missing interoperability is disclosed, never misrepresented as tested.

**Historical publication condition:** RC-A through RC-C were completed for the scoped educational release and `v0.1.0-portfolio` was published. The notes remain a historical draft. Do not use the term production-ready without a separate production acceptance program.
