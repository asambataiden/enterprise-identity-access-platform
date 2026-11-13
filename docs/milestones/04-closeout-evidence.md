# Milestone 04 — Architecture and reliability closeout evidence

**Recorded baseline:** 2026-11-13, `main` at `3350df1`. Evidence below is based on observed local terminal logs and GitHub PR results; it must not be extrapolated to production systems.

## Increment provenance

| Scope | PR | Outcome / qualification |
| --- | --- | --- |
| 04G-1: durable transactional outbox (Flyway V12) | [#32](https://github.com/asambataiden/enterprise-identity-access-platform/pull/32) | Merged; event creation with approval/work-item transaction |
| 04G-2: lease-fenced relay (Flyway V13) | [#33](https://github.com/asambataiden/enterprise-identity-access-platform/pull/33) | Merged; failure/recovery and fencing tests |
| 04G-3: transactional inbox (Flyway V14) | [#34](https://github.com/asambataiden/enterprise-identity-access-platform/pull/34) | Merged; idempotent local receipt and projection |
| 04G-4: in-process E2E relay → inbox | [#35](https://github.com/asambataiden/enterprise-identity-access-platform/pull/35) | Merged; three local E2E integration tests; GitHub Java CI successful |
| Provisioning package refactor | [#36](https://github.com/asambataiden/enterprise-identity-access-platform/pull/36) | Merged at `3350df1`; five cohesive packages, `.gitignore` correction, GitHub Java CI successful |

Earlier Milestone 04 provisioning simulation, quarantine and review increments are documented under [`docs/milestones/`](./). This is a scoped evidence summary, not an exhaustive test-by-test attestation.

## Observed verification

- **After 04G-4:** `mvn -B --no-transfer-progress clean verify` → 13 unit tests + 95 integration tests; **108 passed**, zero failures/errors/skips, exit 0. PostgreSQL 17 Testcontainers and Flyway V1–V14 exercised.
- **After package refactor:** same Java suite → **108 passed**, exit 0. `python3 -m unittest discover -s ops/keycloak -p 'test_*.py'` → **33 passed**.
- **PR #36:** Java CI `verify` success on corrected source tree after restoring six ignored `provisioning/target/` files and anchoring `.gitignore` to `/target/`.
- **Not verified by these checks:** real broker availability, actual grants, deployed IdP/provisioning credentials, cluster readiness, multi-region operation, SLOs, load capacity or formal security compliance.

### Reproduction commands

```bash
java -version
mvn -B --no-transfer-progress clean verify
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
git log -1 --oneline
```

Tests are the current verification gate; record environment, final exit codes, Git commit and workflow URL when evidence is renewed.

## Failure-mode boundaries

| Failure | In-scope protection | Remaining risk / follow-up |
| --- | --- | --- |
| Repeated HTTP submission | Tenant-keyed request idempotency; compare request terms | Requires authenticated actor mapping and rate limits in deployment |
| Worker crash while lease held | Lease expiry and retry/fencing | Stale external effects need target-side idempotency/readback |
| Publish succeeds, acknowledgment lost | Durable relay retry and inbox duplicate no-op | At-least-once delivery still generates duplicate messages |
| Consumer fails before transaction commit | Receipt and projection roll back together | Broker re-delivery and DLQ operations not implemented |
| Fifth failed relay attempt / expired lease | Dead-letter state is persisted | No automated operator replay or alerting policy |
| Uncertain external target outcome | Mock inspection/quarantine evidence | No real target reconciliation; manual decisions require authorization |
| Forged / cross-tenant actor | JWT validation plus local identity/permission binding; tenant-safe database keys | Continuous adversarial security testing remains necessary |
| Repository file silently omitted by ignore pattern | Anchored `/target/` rule plus clean GitHub CI | Future build artifacts in nested modules need module-specific ignore rules |

## Exit assessment

**Milestone 04 reliability slice is complete within the educational/local-test scope.** It does not authorize a production deployment. Follow-on Milestone 05 requires prioritized DevSecOps and operational readiness acceptance criteria, including secret handling, dependency/container/IaC scans where applicable, reproducible deployment, metrics/alerting and recovery tests. Milestone 06 will curate validated architecture and interview evidence.
