# Milestone-by-Milestone Implementation Provenance Map

**Purpose:** connect the chronological `docs/milestones/` implementation and debugging artifacts to the new numbered learning curriculum and distinct business use cases. Existing milestone documents are historical snapshots: an original **“pending”** status does not override code and tests merged later; likewise an early lab success does not mean currently blocked live Keycloak bearer interoperability passed the portfolio acceptance gate.

**Immutable educational release:** `v0.1.0-portfolio` → `cf0e2fce324fa6c81bf5bccc4ee49dadf1925391`. The learning track was added after that tag.

## Foundation, identity, request intake

**Related lessons:** 00–05. Read in this order:

1. [01a-persistence-invariants](../milestones/01a-persistence-invariants.md)
2. [01b-1-jwt-resource-server](../milestones/01b-1-jwt-resource-server.md)
3. [01b-2-authenticated-intake](../milestones/01b-2-authenticated-intake.md)
4. [01b-3-signed-jwt-validation](../milestones/01b-3-signed-jwt-validation.md)
5. [01c-1-local-keycloak-metadata-lab](../milestones/01c-1-local-keycloak-metadata-lab.md)
6. [01c-2-automated-synthetic-user-provisioning](../milestones/01c-2-automated-synthetic-user-provisioning.md)
7. [01c-2-pkce-browser-token-lab](../milestones/01c-2-pkce-browser-token-lab.md)
8. [01c-3-authorized-request-live-token-lab](../milestones/01c-3-authorized-request-live-token-lab.md)

## Approval and identity-lab explorations

**Related lessons:** 06. Read in this order:

1. [03a-approval-decision-persistence-foundation](../milestones/03a-approval-decision-persistence-foundation.md)
2. [03b-approval-service-state-machine](../milestones/03b-approval-service-state-machine.md)
3. [03c-authenticated-approval-api](../milestones/03c-authenticated-approval-api.md)
4. [03d-real-token-requester-binding-assertion](../milestones/03d-real-token-requester-binding-assertion.md)
5. [03d-self-approval-real-token-lab](../milestones/03d-self-approval-real-token-lab.md)
6. [03d-two-identity-keycloak-approval-lab](../milestones/03d-two-identity-keycloak-approval-lab.md)

## Audit and provisioning work

**Related lessons:** 06–07. Read in this order:

1. [04a-transactional-approval-audit](../milestones/04a-transactional-approval-audit.md)
2. [04b-approved-provisioning-intent](../milestones/04b-approved-provisioning-intent.md)
3. [04c-provisioning-lease-recovery](../milestones/04c-provisioning-lease-recovery.md)
4. [04d-simulated-provisioning-completion](../milestones/04d-simulated-provisioning-completion.md)

## Adapter contract and mock reconciliation

**Related lessons:** 08. Read in this order:

1. [04e1-adapter-contract-attempt-evidence](../milestones/04e1-adapter-contract-attempt-evidence.md)
2. [04e2-loopback-http-mock-contract](../milestones/04e2-loopback-http-mock-contract.md)
3. [04e3-mock-orchestration-observation](../milestones/04e3-mock-orchestration-observation.md)
4. [04e4-mock-reconciliation-read-before-write](../milestones/04e4-mock-reconciliation-read-before-write.md)

## Quarantine and reviewer boundary

**Related lessons:** 09. Read in this order:

1. [04f1-readonly-quarantine-inspection](../milestones/04f1-readonly-quarantine-inspection.md)
2. [04f2-quarantine-review-evidence](../milestones/04f2-quarantine-review-evidence.md)
3. [04f3a-authenticated-quarantine-inspection](../milestones/04f3a-authenticated-quarantine-inspection.md)
4. [04f3b-real-token-quarantine-inspection-lab](../milestones/04f3b-real-token-quarantine-inspection-lab.md)
5. [04f3c-reviewer-subject-binding](../milestones/04f3c-reviewer-subject-binding.md)

## Durable event pattern

**Related lessons:** 10. Read in this order:

1. [04g-transactional-outbox](../milestones/04g-transactional-outbox.md)
2. [04g2-outbox-delivery-relay](../milestones/04g2-outbox-delivery-relay.md)
3. [04g3-transactional-inbox](../milestones/04g3-transactional-inbox.md)
4. [04g4-end-to-end-event-flow](../milestones/04g4-end-to-end-event-flow.md)
5. [04-closeout-evidence](../milestones/04-closeout-evidence.md)

## Security controls, testing and evidence

**Related lessons:** 04/11. Read in this order:

1. [05a1-ci-security-baseline](../milestones/05a1-ci-security-baseline.md)
2. [05a2-java-sast](../milestones/05a2-java-sast.md)
3. [05a3-adversarial-iam-security-tests](../milestones/05a3-adversarial-iam-security-tests.md)
4. [05a4-http-authorization-regressions](../milestones/05a4-http-authorization-regressions.md)
5. [05a-security-closeout](../milestones/05a-security-closeout.md)
6. [05b1-spotbugs-analysis-coverage](../milestones/05b1-spotbugs-analysis-coverage.md)
7. [05b2-github-actions-supply-chain](../milestones/05b2-github-actions-supply-chain.md)
8. [05b3-reproducible-ci-evidence](../milestones/05b3-reproducible-ci-evidence.md)
9. [05b4-security-assurance-closeout](../milestones/05b4-security-assurance-closeout.md)

## Runtime metadata and signed JWT portfolio regression

**Related lessons:** 05/12. Read in this order:

1. [06a-keycloak-runtime-smoke](../milestones/06a-keycloak-runtime-smoke.md)
2. [06b2-offline-signed-jwt-http-regression](../milestones/06b2-offline-signed-jwt-http-regression.md)

## How to read these notes correctly

1. Start with [Learning Lesson 00](00-orientation-and-prerequisites.md) and follow 00–12, not the milestone directory's lexical order alone.
2. At each increment, review business requirement, affected migrations/source, tests and original debugging notes.
3. Cross-check current implementation against the [capability matrix](../architecture/capability-matrix.md), [complete engineering guide](../portfolio/complete-engineering-guide.md) and [use-case index](../use-cases/README.md).
4. For a claim of completion, prefer a merged PR, observed test logs, matching SHA and [release evidence](../release/evidence-register.md). Do not claim every original draft acceptance checkbox was individually rerun after the tag.
5. For failed or unresolved work (especially real Keycloak PKCE/JWKS bearer interoperability in draft PR #49), preserve historical experimental notes without converting them to passing release evidence.

## Practical learning capture template

For every increment or future investigation, record: **problem → business/use-case ID → threat model → decision/alternatives → changed files and migration → Java/SQL/control-flow diagram → positive and negative tests → exact evidence SHA and CI URL → failures/root causes → operational cleanup → what I learned → interview questions → remaining production gaps**.

## Release facts versus historical phases

- Published portfolio version: `v0.1.0-portfolio`, fixed to the original tested commit.
- Recorded release regression: 16 unit + 105 integration Java tests, 33 + 22 Python tests; staged demo was 5+46 additional **executions**, not extra distinct test cases.
- Security jobs: Java CI verify; Gitleaks; Trivy; SpotBugs + Find Security Bugs succeeded on the tagged release commit.
- **Unimplemented/unverified** production broker, real entitlement provisioning, HA/SLO/backups, and currently blocked live Keycloak token-to-API proof.

**Next:** [Curriculum index](README.md) · [Architecture index](../architecture/README.md) · [Use-case index](../use-cases/README.md).
