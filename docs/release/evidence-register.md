# IAM portfolio — release evidence and decision record

**Prepared:** 2026-11-14. **Document preparation baseline:** merged `main` commit `4a2cb72` after PR #52. This document is a preparation-stage evidence ledger. **The eventual release commit has not yet been qualified or tagged.**

## Observed scoped evidence (immutable references)

| Verification | Commit / source | Observation | Boundary |
| --- | --- | --- | --- |
| Signed JWT HTTP filter | merged [PR #50](https://github.com/asambataiden/enterprise-identity-access-platform/pull/50), `f71f9a9` | Local 5 signed JWT unit tests + 8 signed-JWT HTTP IT passed; [Java CI](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43548511758) and [Security CI](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43548511783) succeeded | Ephemeral RSA signing keys and a **test-only decoder**, not live Keycloak JWKS interoperability |
| Release scope freeze | merged [PR #51](https://github.com/asambataiden/enterprise-identity-access-platform/pull/51), `03652ae` | All four PR checks passed; release gate register added | Documentation and planning; not an accepted release |
| Deterministic curated demo | [PR #52](https://github.com/asambataiden/enterprise-identity-access-platform/pull/52), PR-head `4f9f563a79443f88b0193e709aa3703654820a87`, merged `4a2cb72` | Developer observed 5 unit + 46 integration tests, zero failures/errors; `BUILD SUCCESS`, final script `PASS` | Independent Testcontainers fixtures and simulated target; not one continuous external API transaction |
| PR #52 hosted CI | [Java CI run 43549555388](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43549555388) and [Security CI run 43549555417](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43549555417) | Java verify, Gitleaks, Trivy and SpotBugs/Find Security Bugs all **success** | Evidence on **PR-head**; it is not a substitute for verifying this final release commit |
| Real Keycloak PKCE HTTP bearer test | [draft PR #49](https://github.com/asambataiden/enterprise-identity-access-platform/pull/49) | Browser login/admin credentials blocked; no live access token accepted over HTTP | **Unverified**, deliberately not an educational release blocker |
| Core domain and transaction reliability | [as-built architecture](../architecture/system-overview.md), [04 closeout](../milestones/04-closeout-evidence.md) | Documented request, approvals, retries, quarantine, outbox/inbox test coverage | No broker listener or effective real entitlement |

## Release-candidate evidence still required

- [ ] **Release-head commit SHA:** capture `git rev-parse HEAD` *after* merging final release documentation. Never re-label PR-head evidence with a different SHA.
- [ ] **Full Java regression:** `mvn -B --no-transfer-progress clean verify` passes at that commit, including all Surefire and Failsafe tests.
- [ ] **Other test suites:** run Python test discovery under `ops/keycloak` and `ops/security`; capture counts and failures.
- [ ] **Current GitHub checks:** four green jobs for the release-head `main` commit or corresponding final PR-head with an explicit SHA; record the URLs.
- [ ] **Release demonstration:** rerun `bash ops/demo/run-portfolio-demo.sh` on merged `main`, record Maven counts and `PASS`. No Keycloak credentials.
- [ ] **Reviewer sign-off:** review docs/runbook, invariants, redaction and capability claims; record reviewer and date.
- [ ] **Tag approval:** only create a Git tag after verifying all accepted educational release gates. Do not tag a known failing or unverified release candidate as completed.

## Reproducible execution

```bash
git status --short --branch
git rev-parse HEAD
mvn -B --no-transfer-progress clean verify
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
python3 -m unittest discover -s ops/security -p 'test_*.py'
bash ops/demo/run-portfolio-demo.sh
```

Ignore pre-existing **untracked local helper scripts** when assessing Git working tree cleanliness; they are not part of this release and must not be deleted or accidentally staged. Confirm tracked changes separately with `git diff --exit-code` and `git diff --cached --exit-code`. Do not indiscriminately run `git clean -fd` or `git add .`.

## Architecture / learning / known limitations

- [As-built trust boundaries and sequence diagram](../architecture/system-overview.md)
- [Capabilities present and explicitly absent](../architecture/capability-matrix.md)
- [Threat model](../security/threat-model.md)
- [Demo runbook and demonstration sequence](portfolio-demo-runbook.md)
- [Local operations, rollback and known failures](local-operations-runbook.md)
- [Final release criteria](portfolio-release-gates.md)

**Known limitations that must appear publicly:** no authenticated real Keycloak integration proof, production SCIM/target provisioning, production broker/scheduled workers, production HA, tested backup/restore, monitoring SLOs, live traffic performance evaluation or compliance certification.

## Release decision

**Current status: candidate documentation prepared, NOT tagged / NOT production-ready.**

Final release verdict requires the unchecked gates above to be observed and recorded against the final immutable main commit. Educational portfolio approval is explicitly different from external product security sign-off.
