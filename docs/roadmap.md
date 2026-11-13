# Delivery roadmap — evidence-gated

**As-built snapshot:** `main` commit `3350df1` (2026-11-13). “Completed” below means the scoped repository/lab acceptance evidence exists; no production launch or compliance claim is implied.

| Milestone | Scope | State / remaining gate |
| --- | --- | --- |
| 00 | Repository, ADRs, threat model, Java baseline, CI | Implemented; baseline documented |
| 01 | Tenant-scoped request persistence, OIDC/JWT application boundary, local Keycloak lab | Implemented for controlled lab/test scope; production deployment not demonstrated |
| 02 | Identity trust validation and actor mapping (functional scope from original roadmap) | Exercised through JWT/HTTP and local lab scenarios; retain requirement mapping for full production gate |
| 03 | Approval workflow, separation-of-duties checks and delegated permissions | Implemented in application with HTTP and database tests; real-grant policy not deployed |
| 04 | Simulated provisioning, reconciliation evidence, quarantine, transactional outbox/inbox | Completed as **mock/local reliability slice**, through PR #36; no live provisioning or broker |
| 05 | DevSecOps, reproducible deployment, security scanning, observability, operations | **Next:** define and implement measurable deployment/security/operational gates |
| 06 | Portfolio release, architecture and interview workbook | Planned; publish only validated capabilities and evidence |

Milestone numbering reflects the original roadmap intent; some earlier milestone notes use incremental labels (01B, 01C, 04G). Refer to [capability matrix](architecture/capability-matrix.md) and [04 closeout](milestones/04-closeout-evidence.md) for precise scope.

**Milestone 05 suggested acceptance gates:** reproducible least-privilege configuration and deployment; actionable dependency/SAST/secrets scanning with justified false-positive handling; telemetry for HTTP errors, outbox delay and DLQ count where components are actually running; backup/restore and failure drills in a safe environment; redacted CI evidence. Do not require multicloud, Kafka or Kubernetes absent a tested need.

**Not on critical path:** broad SCIM/federation, multicloud, generic policy engine and microservice decomposition unless a verified business use case justifies them.
