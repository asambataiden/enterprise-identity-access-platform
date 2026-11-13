# Milestone 05A-1 — CI security baseline

## Goal
Add narrow, independently observable CI security gates without changing IAM runtime behavior, Maven dependencies, SQL migrations or production infrastructure.

## Changes
- `.github/workflows/security-ci.yml` runs Gitleaks v3 for leaked secrets on PRs and pushes to main, using full checkout history. PR comments and leak-report artifact uploads are disabled to reduce exposure of sensitive scanner findings. **Detection is not remediation:** rotate any genuinely exposed credential immediately and investigate Git history.
- The security workflow builds the Spring Boot executable JAR with Maven (`-DskipTests package`) and runs Trivy v0.36.0 filesystem vulnerability scanning over the checked-out repository and build output. HIGH or CRITICAL findings fail the job. Unlike GitHub Dependency Review, this is a **current dependency inventory gate**, not a PR-only dependency diff, and does not require GitHub Dependency Graph availability.
- `.github/workflows/java-ci.yml` runs all `ops/keycloak/test_*.py` tests via unittest discovery, instead of explicitly calling only two fixture tests. A full runtime/Keycloak live-lab test is not run in hosted CI.

## Security boundaries and limitations
- GitHub-hosted jobs use read-only repository token permissions. No `pull_request_target`, cloud credentials, privileged host provisioning or automatic secret rotation.
- The repository belongs to a personal GitHub account; gitleaks-action v3 documents no license requirement for personal-account repositories. Its runner uses Node 24.
- Scanner releases use documented major release refs. For stronger supply-chain control, independently resolve and pin immutable commit SHAs in a later increment.
- Gitleaks may flag benign synthetic lab fixtures. Investigate before creating narrow, documented suppressions; do not disable the job or broadly exclude `ops/keycloak`.
- GitHub Dependency Review was attempted in PR #38 but failed because the repository does not support the Dependency Graph API. Trivy replaces that unavailable check; no vulnerability was reported by the rejected GitHub job.
- Trivy requires vulnerability database access at scan time; database download/network failures should fail the job, not be silently ignored. Trivy's inventory is limited by package detection and the bundled JAR's resolvable component metadata; a successful scan does not prove the absence of all vulnerabilities.
- **Not included:** CodeQL/SAST, container-image scanning, signing/SBOM provenance, deployment hardening, production observability, or security certification.

## Validation
```bash
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
python3 -m py_compile ops/keycloak/*.py
mvn -B --no-transfer-progress clean verify
# After creating a PR: gh pr checks --watch
```

Record the observed results, Gitleaks and Trivy versions, current PR head SHA and hosted workflow links before making a completion claim. Never paste secrets or unredacted Gitleaks findings into public documentation.

## Interview reasoning
The separation between build tests, leak scanning and current dependency vulnerability checks produces clear failure ownership. CI fails closed while avoiding permission escalation. Unlike a dependency-diff review, a baseline scan may discover pre-existing issues requiring remediation or a reviewed, narrowly scoped exception.
