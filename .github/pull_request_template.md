## Objective / business use case

What requirement or security invariant is changed, and why is this change necessary?

## Scope and security boundaries

- [ ] API contracts, tenant authority and default-deny security rules are preserved or intentionally reviewed.
- [ ] No accidental real IAM grant, broker or production readiness claims.
- [ ] New external dependencies or Maven/workflow changes have a version and threat rationale.
- [ ] Local secrets, JWTs, credentials and untracked personal scripts are excluded.

## Changes and design decisions

Summarize implementation, SQL migration (if any), backwards compatibility, concurrency, failure cases and any OpenAPI contract impact.

## Evidence

- Git SHA tested:
- Focused local test commands and observed results:
- Expected Java CI `verify` check:
- Expected Security CI: Secret detection / Trivy / SpotBugs + Find Security Bugs:
- Negative/boundary cases:
- Explicit limitations:

## Rollout / rollback and documentation

Explain how to validate after merge, update affected lessons/use cases/ADRs, and handle regressions safely.

## Review

- [ ] Four required GitHub checks are green at current PR revision.
- [ ] All review conversations resolved.
- [ ] PR is ready for review; no merge with failing/pending checks.
- [ ] No bypass of branch protection or weakening of evidence policy.
