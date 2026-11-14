# IAM capstone release candidate — scope freeze and acceptance register

**Audit baseline:** `main` at `f71f9a9`, 2026-11-14, after merged PR #50. This register is a **review checklist**, not a declaration that the whole platform is ready for production.

## Product statement

The enterprise IAM capstone is an **educational local-first access-governance modular monolith**, demonstrating tenant-scoped request/approval controls, simulated provisioning, quarantine evidence, transactional outbox/inbox and a defense-in-depth JWT API boundary.

It is **not a deployed identity provider**, a real entitlement provisioning engine, a production SCIM implementation, or a certified IAM security product. It does not execute live third-party grants.

## Evidence available now

| Area | Grounded repository evidence | Interpretation |
| --- | --- | --- |
| Core request/approval workflow | [Milestone 04 closeout](../milestones/04-closeout-evidence.md), [as-built architecture](../architecture/system-overview.md), HTTP/database tests | Implemented for controlled local and CI environments |
| Provisioning lease/retry/quarantine | V1–V14 migrations, mock adapter, local test bridge, quarantine tests | **Simulation**; approved intent and RECEIVED do not mean granted access |
| Transactional outbox/inbox | V12–V14, lease-fenced relay and deduplicated inbox | Local durable correctness slice; no production broker/listener |
| Keycloak OIDC discovery/JWKS | [06A evidence](../milestones/06a-keycloak-runtime-smoke.md), merged PR #47 | Local metadata smoke passed; does not authenticate a real user |
| Signed JWT decoder and HTTP filter | [06B-2](../milestones/06b2-offline-signed-jwt-http-regression.md), merged PR #50 | Real RSA-signed test JWTs through filter chain using **test-only decoder**; live Keycloak not proven |
| CI and supply-chain hardening | Java CI, Security CI (Gitleaks, Trivy, SpotBugs/Find Security Bugs), pinned workflows and evidence artifacts | CI gates pass on PR #50; not equivalent to a third-party security assessment |
| Real Keycloak browser PKCE token interoperability | [Draft PR #49](https://github.com/asambataiden/enterprise-identity-access-platform/pull/49) | **Unverified** due to synthetic test login/admin credentials; keep separate and time-boxed |
| Real provisioning, production deployment, backup/restore, SLO monitoring | [Capability matrix](../architecture/capability-matrix.md) | Not demonstrated; explicitly excluded from educational release claims |

## Release definition of done — no new feature expansion

This is the **portfolio release candidate**, not a production go-live. Every row requires observed evidence before marking complete.

- [ ] **RC-01 Full regression:** fresh `mvn -B --no-transfer-progress clean verify` on release-head commit with all tests successful, including Testcontainers; retain CI link and commit SHA.
- [ ] **RC-02 Security gates:** Java CI, Gitleaks, Trivy and SpotBugs/Find Security Bugs all green at **same release-head commit**; vulnerabilities not silently suppressed.
- [ ] **RC-03 Scope truthfulness:** README, roadmap, capability matrix, threat model and diagrams consistently distinguish local/test-only mechanisms from unimplemented production capabilities.
- [ ] **RC-04 Repeatable local demo:** run [the staged Testcontainers portfolio demo](portfolio-demo-runbook.md) via `bash ops/demo/run-portfolio-demo.sh` and record zero test failures. The stages use **independent fixtures**, not one shared request/session; test seed SQL is setup, not an HTTP API call. Live Keycloak is not required, and no real entitlement is granted.
- [ ] **RC-05 Operating notes:** document prerequisites, startup/shutdown, expected health check, isolated test database, log redaction, rollback limitations and known failure scenarios.
- [ ] **RC-06 Evidence index:** record the demo and regression command/results, immutable commit, CI URLs, architecture/sequence diagrams and outstanding limitations without committing token/secret values.
- [ ] **RC-07 Reviewer sign-off:** source code and evidence reviewed for ownership, least privilege, bad-path behavior, test coverage and portfolio accuracy.

### Deferred — explicitly **not** release-candidate blockers

- A real PKCE login against the developer's broken Keycloak user/admin state. No false claim of live token compatibility. Revisit under a bounded integration exercise with separately provisioned lab only if needed.
- Live third-party entitlement provisioning, SCIM, production broker, multicloud/Kubernetes, dynamic policy engine or microservice decomposition.
- Production HA, backups/restoration, operational SLOs and external security attestation. These are **production readiness** blockers, not educational portfolio release blockers.

## Bounded next increments

1. **RC-A: Documentation truthfulness and demo specification.** This PR adds the register and aligns stale entry-point docs, without changing Java or infrastructure.
2. **RC-B: Deterministic demonstration.** [One command](portfolio-demo-runbook.md) selects existing HTTP, signed-JWT, simulated provisioning, outbox/inbox and quarantine tests. Acceptance requires observed local run results; selected tests are separate scenarios rather than a single seamless API session.
3. **RC-C: Final verification and release note.** Run full CI, record immutable evidence and explicit limits, then tag only when checklist gates are met.

Stop adding features outside these three increments unless an observed failing acceptance check forces a minimal correction.

## Minimal validation commands

```bash
mvn -B --no-transfer-progress clean verify
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
python3 -m unittest discover -s ops/security -p 'test_*.py'
```

Running `mvn spring-boot:run` against the default datasource requires the operator's own isolated local PostgreSQL; the GitHub CI integration tests provision disposable PostgreSQL containers. Do not connect a demo to any shared or production database.

## Interview-ready explanation

**Why modular monolith?** One deployment retains cohesive transactional boundaries across request, approval and evidence while allowing domain-oriented packages. The project does not claim independent microservice fault isolation.

**Why no live entitlement?** In this capstone, approved intent, dispatched event and inbox projection are separate states. Effective access must be asserted against an external authoritative target, which is beyond the scope of the simulated adapter.

**Why no live Keycloak requirement for the deterministic demo?** Security controls are validated at different boundaries: Spring Security mocked-principal authorization tests, real RSA-signed offline HTTP tests, and local IdP metadata checks. Live OIDC interoperability remains unverified and must never be relabeled as passed.

**Why a scope freeze?** It makes completion, reproducibility and truthful risk reporting the primary engineering deliverables instead of endlessly expanding the architecture.
