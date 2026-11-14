# GitHub main-branch governance — Enterprise IAM portfolio

## Why this exists

On **2026-11-14**, the main branch reported `Branch not protected` (GitHub CLI HTTP 404), and the repository Rulesets list returned **[]**. PR #65 was merged while `Java CI/verify` was failing; PR #66 subsequently corrected that contract mismatch and passed all configured CI gates. That was a **merge-policy gap**, not a reason to weaken the OpenAPI drift assertion.

This repository is presently owned/operated as a **single-maintainer educational portfolio**. This policy is designed to block regressions **without requiring a second GitHub reviewer who does not exist**. It is **not** a substitute for an independent professional code/security review.

## Recommended enforceable policy

| Control | Configuration | Reason |
| --- | --- | --- |
| Target | Exactly `main` | Protect working default branch |
| Require pull request | Yes | Do not push or merge directly |
| Human approval count | **0 for solo maintainer** | GitHub authors cannot approve their own PR; requiring one without a real collaborator blocks progress |
| Require resolved conversations | Yes | Prevent merging unresolved change requests |
| Require successful checks | **All 4** listed below | Prevent repeat of PR #65 regression |
| Require current base (`strict`) | Yes | Detect outdated PRs and re-run after other merges |
| Enforce for administrators | Yes | No accidental owner bypass |
| Linear history | Yes | Matches squash-merge workflow |
| Force pushes / deletions | Disabled | Protect history and branch integrity |
| External contributor review | Optional, recommended when available | A real second reviewer enables 1 required approval |

**Required exact GitHub check-run names:** obtained using the GitHub Checks API on commit `64e547f`; all were emitted by GitHub Actions App `15368`.

- `verify` — Java CI workflow.
- `Secret detection` — Security CI Gitleaks job.
- `Dependency vulnerabilities (Trivy)` — Security CI scanner.
- `Java static analysis (SpotBugs + Find Security Bugs)` — Security CI SAST job.

These are **check-run context names**, not the UI's combined `Workflow / Job` labels. This payload uses the REST API's `contexts` representation. Do not send both `contexts` and `checks` in the same `required_status_checks` object: the REST schema treats them as mutually exclusive alternatives and returns HTTP 422 when both are present. The separate `checks` representation supports app IDs, but this policy uses the verified context names alone. Requiring `Java CI/verify` as a literal check name would be incorrect. See [GitHub required checks documentation](https://docs.github.com/en/rest/branches/branch-protection).

## Planned settings are not automatically active

[`main-branch-protection.json`](main-branch-protection.json) is a proposed GitHub classic branch-protection request body, not an executable deployment or a claim that branch protection has been enabled.

After the documentation PR is merged **and all existing CI checks have passed**, review the JSON carefully. For a personal public repository where the authenticated account has administrator privileges, apply **once**:

```bash
# Read-only: confirm repo/permissions and current protection before changing settings.
gh repo view asambataiden/enterprise-identity-access-platform \
  --json nameWithOwner,defaultBranchRef
gh api repos/asambataiden/enterprise-identity-access-platform/rulesets
gh api repos/asambataiden/enterprise-identity-access-platform/branches/main/protection \
  || true  # Expected 404 only if no classic protection exists

# Review the actual policy you are about to apply:
python3 -m json.tool docs/governance/main-branch-protection.json

# MUTATING: only after explicitly choosing to activate the reviewed policy.
gh api --method PUT \
  repos/asambataiden/enterprise-identity-access-platform/branches/main/protection \
  --input docs/governance/main-branch-protection.json
```

This action requires a GitHub CLI token with **repository Administration (write)** access. The connected GitHub integration has read access for rulesets but cannot perform this admin-level mutation; therefore this repository file **does not enforce any protection until the owner runs the command**.

If GitHub returns 403, check `gh auth status`, repository role and token scopes rather than weakening policy. If it returns 422, inspect and adjust GitHub's validation error; in particular verify exact context names, availability, and review settings. Do not blindly submit a partial protection payload that omits the four gates.

### Prove the applied policy

```bash
# Expected: HTTP 200 and a branch-protection JSON document, not 404.
gh api repos/asambataiden/enterprise-identity-access-platform/branches/main/protection \
  --jq '{required_status_checks, enforce_admins, required_pull_request_reviews, required_conversation_resolution, required_linear_history, allow_force_pushes, allow_deletions}'

# Inspect GitHub's source check-run names from a successful commit:
gh api repos/asambataiden/enterprise-identity-access-platform/commits/main/check-runs \
  --jq '[.check_runs[] | {name, conclusion, app: .app.slug}]'
```

**Real enforcement exercise:** open a disposable **draft** PR from a harmless documentation branch, confirm the four required checks appear, then test a failure using a separate intentionally failing PR **without merging**. Do not modify main or force-push to simulate failure. Verify GitHub prevents merging red checks, and close the deliberately failing PR afterward. A green check must be evaluated against the latest required base when strict update is enabled.

## Operating workflow after protection

1. Branch from current `main`; keep the change narrowly scoped.
2. Include business requirement, threat boundary, implementation, test evidence and rollback considerations in the PR description.
3. Run focused local verification, then require all four CI checks green on the current PR head/synthetic merge.
4. Resolve conversations; ensure the PR is not draft. For a one-person repository, no self-approval is required.
5. Squash merge using GitHub's protected workflow. Check `git rev-parse HEAD`, `gh pr view`, and `git pull --ff-only` after merge.
6. Record tested SHA, GitHub run URLs, result/limitations and relevant documentation changes.
7. If a required check fails: **fix the cause in the PR branch**; never weaken/deactivate the required check to merge.

## Protecting local generated artifacts

The tracked `.gitignore` now excludes `*.iml` and Python `__pycache__/` folders to avoid accidental IDE/runtime noise. These entries **do not remove local files**. Existing untracked engineering scripts under `ops/` are deliberately not ignored or deleted; review them individually before deciding whether they belong in source control.

## Learning and interview explanation

**Why enforce all four check jobs instead of the two workflow names?** GitHub branch protection operates on **check-run contexts**; the Security CI workflow has three separate jobs with independent pass/fail evidence. Any one of those should be able to block a merge.

**Why zero approvals?** The personal portfolio repository currently has one maintainer. A real author cannot approve their own change; artificially requiring one approval could permanently block the maintenance workflow. A future collaborator can enable one or two independent required reviewers.

**Why enforce administrator protection?** PR #65 demonstrates that a green documentation or security subset does not compensate for a failing Java contract test. Requiring the owner to pass the same checks closes this bypass route.

**What is not proven by this file?** Rules are **not active** until the GitHub REST API returns successful activation and the verification output shows all required checks. A green CI result is not independent security attestation or production readiness.

## Source references

- [GitHub — Branch protection settings](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [GitHub — Branch protection REST API](https://docs.github.com/en/rest/branches/branch-protection)
- [Java CI workflow](../../.github/workflows/java-ci.yml)
- [Security CI workflow](../../.github/workflows/security-ci.yml)
- [OpenAPI contract governance](../api/README.md)
