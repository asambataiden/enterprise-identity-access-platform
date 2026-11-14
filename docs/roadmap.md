# Delivery roadmap — evidence-gated

**Release audit baseline:** `main` commit `f71f9a9` (2026-11-14). The Milestone 04 as-built architecture snapshot remains `3350df1`. “Completed” means scoped local/CI evidence; no production deployment or compliance claim is implied. See the [portfolio release gates](release/portfolio-release-gates.md).

| Milestone | Scope | State / remaining gate |
| --- | --- | --- |
| 00 | Repository, ADRs, threat model, Java baseline, CI | Implemented; baseline documented |
| 01 | Tenant-scoped request persistence, OIDC/JWT application boundary, local Keycloak lab | Implemented for controlled lab/test scope; production deployment not demonstrated |
| 02 | Identity trust validation and actor mapping (functional scope from original roadmap) | Exercised through JWT/HTTP and local lab scenarios; retain requirement mapping for full production gate |
| 03 | Approval workflow, separation-of-duties checks and delegated permissions | Implemented in application with HTTP and database tests; real-grant policy not deployed |
| 04 | Simulated provisioning, reconciliation evidence, quarantine, transactional outbox/inbox | Completed as **mock/local reliability slice**, through PR #36; no live provisioning or broker |
| 05 | DevSecOps/security CI and operational hardening | Secret scanning, Trivy, SAST, pinned workflows and evidence artifacts implemented; production deployment, SLOs and restoration drills **not** verified |
| 06 | Signed-token HTTP regression and portfolio candidate release | Offline signed-JWT HTTP tests merged in PR #50; live Keycloak PKCE interoperability blocked in draft PR #49. Release candidate checklist pending |

Milestone numbering reflects the original roadmap intent; some earlier milestone notes use incremental labels (01B, 01C, 04G). Refer to [capability matrix](architecture/capability-matrix.md) and [04 closeout](milestones/04-closeout-evidence.md) for precise scope.

**Milestone 05 remaining production-oriented acceptance gates (not automatically required for portfolio release):** reproducible least-privilege configuration and deployment; actionable dependency/SAST/secrets scanning with justified false-positive handling; telemetry for HTTP errors, outbox delay and DLQ count where components are actually running; backup/restore and failure drills in a safe environment; redacted CI evidence. Do not require multicloud, Kafka or Kubernetes absent a tested need.

**Not on critical path:** broad SCIM/federation, multicloud, generic policy engine and microservice decomposition unless a verified business use case justifies them.

**Scope freeze (2026-11-14):** Complete [RC-A through RC-C](release/portfolio-release-gates.md) before adding new IAM features. A real external entitlement, high-availability service or production security assurance is outside this educational release.
