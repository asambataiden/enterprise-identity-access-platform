# Milestone 05A-1 — CI security baseline

## Goal
Add narrow, independently observable CI security gates without changing IAM runtime behavior, Maven dependencies, SQL migrations or production infrastructure.

## Changes
- `.github/workflows/security-ci.yml` runs Gitleaks v3 for leaked secrets on PRs and pushes to main, using full checkout history. PR comments and leak-report artifact uploads are disabled to reduce exposure of sensitive scanner findings. **Detection is not remediation:** rotate any genuinely exposed credential immediately and investigate Git history.
- The same workflow invokes GitHub Dependency Review v5 **on pull requests only**, rejecting newly introduced dependencies reported as HIGH or CRITICAL. This is a *change-level* gate, not a baseline inventory of all transitively vulnerable dependencies. It requires supported GitHub dependency graph/API availability.
- `.github/workflows/java-ci.yml` runs all `ops/keycloak/test_*.py` tests via unittest discovery, instead of explicitly calling only two fixture tests. A full runtime/Keycloak live-lab test is not run in hosted CI.

## Security boundaries and limitations
- GitHub-hosted jobs use read-only repository token permissions. No `pull_request_target`, cloud credentials, privileged host provisioning or automatic secret rotation.
- The repository belongs to a personal GitHub account; gitleaks-action v3 documents no license requirement for personal-account repositories. Its runner uses Node 24.
- Scanner releases use documented major release refs. For stronger supply-chain control, independently resolve and pin immutable commit SHAs in a later increment.
- Gitleaks may flag benign synthetic lab fixtures. Investigate before creating narrow, documented suppressions; do not disable the job or broadly exclude `ops/keycloak`.
- Dependency Review may require GitHub dependency graph permissions/availability. If unsupported, treat it as a platform configuration issue, not a passing security scan.
- **Not included:** CodeQL/SAST, a full dependency vulnerability baseline, image scanning, signing/SBOM, deployment hardening, production observability, or security certification.

## Validation
```bash
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
python3 -m py_compile ops/keycloak/*.py
mvn -B --no-transfer-progress clean verify
# After creating a PR: gh pr checks --watch
```

Record the observed results, Gitleaks/Dependency Review versions, latest PR head SHA and links to hosted workflow runs before making any completion claim. Never paste secrets or unredacted Gitleaks findings into public documentation.

## Interview reasoning
The separation between build tests, leak scanning and dependency-diff checks produces clear failure ownership. CI is designed to fail closed while avoiding permission escalation; this is one defense layer and not proof that the software has no vulnerabilities.
