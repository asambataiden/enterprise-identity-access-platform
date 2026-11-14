# IAM portfolio — release evidence and decision record

## Final published portfolio release (supersedes historical candidate status below)

**Published on 2026-11-14:** annotated Git tag **`v0.1.0-portfolio`** points to immutable release commit **`cf0e2fce324fa6c81bf5bccc4ee49dadf1925391`**. Repository owner confirmed creating/pushing the tag; the remote annotated tag was independently checked. The final technical test and CI evidence was recorded in the discussion of [merged PR #53](https://github.com/asambataiden/enterprise-identity-access-platform/pull/53).

| Final release-head validation at `cf0e2fc` | Observed result | Meaning |
| --- | --- | --- |
| `mvn -B --no-transfer-progress clean verify` | **16 unit + 105 PostgreSQL integration tests**, no failures/errors/skips, `BUILD SUCCESS` | Scoped Java/PostgreSQL behavior, not external IAM deployment |
| `python3 -m unittest discover -s ops/keycloak -p 'test_*.py'` | **33 tests OK** | Local Keycloak helper/fixture validation, not live positive token interoperability |
| `python3 -m unittest discover -s ops/security -p 'test_*.py'` | **22 tests OK** | Security/CI utility behavior |
| `bash ops/demo/run-portfolio-demo.sh` | **5 unit + 46 integration executions**, `PASS` | Selected rerun subset of the 121 Java tests; independent fixtures, not one continuous deployed user journey |
| [Java CI push run 43550333575](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43550333575) | **Success** | Java verification on exact release SHA |
| [Security CI push run 43550333582](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43550333582) | **Success** | Gitleaks, Trivy and SpotBugs + Find Security Bugs successful on exact release SHA |

**Release disposition:** accepted and tagged for an **educational portfolio**, *not* a production readiness authorization, live Keycloak interoperability sign-off, external entitlement-grant claim, deployment security audit or independent compliance certification. Draft [PR #49](https://github.com/asambataiden/enterprise-identity-access-platform/pull/49) remains a separately gated live Keycloak/PKCE experiment.

**History preserved:** the preparation-stage text/checklist below was written before the final release and remains useful to learn the original gates. Its "NOT tagged" wording is a **dated historical snapshot**, not the project's current publication status. Subsequent documentation-only merges do not change the tested tag SHA or the meaning of its tests.

---

**Historical release-candidate preparation (before publication):** 2026-11-14. **Document preparation baseline:** merged `main` commit `4a2cb72` after PR #52. This document is a preparation-stage evidence ledger. **The eventual release commit has not yet been qualified or tagged.**

## Observed scoped evidence (immutable references)

| Verification | Commit / source | Observation | Boundary |
| --- | --- | --- | --- |
| Signed JWT HTTP filter | merged [PR #50](https://github.com/asambataiden/enterprise-identity-access-platform/pull/50), `f71f9a9` | Local 5 signed JWT unit tests + 8 signed-JWT HTTP IT passed; [Java CI](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43548511758) and [Security CI](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43548511783) succeeded | Ephemeral RSA signing keys and a **test-only decoder**, not live Keycloak JWKS interoperability |
| Release scope freeze | merged [PR #51](https://github.com/asambataiden/enterprise-identity-access-platform/pull/51), `03652ae` | All four PR checks passed; release gate register added | Documentation and planning; not an accepted release |
| Deterministic curated demo | [PR #52](https://github.com/asambataiden/enterprise-identity-access-platform/pull/52), PR-head `4f9f563a79443f88b0193e709aa3703654820a87`, merged `4a2cb72` | Developer observed 5 unit + 46 integration tests, zero failures/errors; `BUILD SUCCESS`, final script `PASS` | Independent Testcontainers fixtures and simulated target; not one continuous external API transaction |
| PR #52 hosted CI | [Java CI run 43549555388](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43549555388) and [Security CI run 43549555417](https://github.com/asambataiden/enterprise-identity-access-platform/actions/runs/43549555417) | Java verify, Gitleaks, Trivy and SpotBugs/Find Security Bugs all **success** | Evidence on **PR-head**; it is not a substitute for verifying this final release commit |
| Real Keycloak PKCE HTTP bearer test | [draft PR #49](https://github.com/asambataiden/enterprise-identity-access-platform/pull/49) | Browser login/admin credentials blocked; no live access token accepted over HTTP | **Unverified**, deliberately not an educational release blocker |
| Core domain and transaction reliability | [as-built architecture](../architecture/system-overview.md), [04 closeout](../milestones/04-closeout-evidence.md) | Documented request, approvals, retries, quarantine, outbox/inbox test coverage | No broker listener or effective real entitlement |

## Historical candidate checklist (subsequently satisfied for educational publication)

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

**Historical status at candidate preparation: NOT YET tagged / NOT production-ready.** The release was subsequently tagged as described in the opening final-release addendum; the production-readiness exclusion still applies.

Final release verdict requires the unchecked gates above to be observed and recorded against the final immutable main commit. Educational portfolio approval is explicitly different from external product security sign-off.
