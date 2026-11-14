# Capability matrix — implemented versus planned

**As-built Milestone 04 snapshot:** `3350df1`, 2026-11-13. **Release audit update:** `f71f9a9`, 2026-11-14. “Implemented” means code and scoped tests exist, not production certification. See [release acceptance](../release/portfolio-release-gates.md).

| Area | Current status | Evidence / limit |
| --- | --- | --- |
| Tenant-scoped access-request persistence and idempotency | Implemented | PostgreSQL/Flyway constraints; service and HTTP tests |
| JWT resource server with issuer/JWKS/audience trust checks | Implemented in application and controlled tests | Keycloak local lab; no internet-exposed production service |
| Requester and approval actor binding | Implemented for HTTP paths | Local employee mapping, permission checks and negative test scenarios |
| Approval workflow and transactional audit | Implemented | Approved intent is not effective access |
| Provisioning lease, retry, attempt observations and mock adapter | Implemented in controlled simulation | No real managed application entitlement |
| Quarantine inspector and reviewer evidence | Implemented with read-only HTTP inspection | Review journal is not a public remediation API |
| Transactional outbox V12 and lease-fenced relay V13 | Implemented, relay explicitly invoked | No live broker or background scheduler |
| Transactional inbox V14 and RECEIVED projection | Implemented | Deduplication of local projection; no external exactly-once guarantee |
| End-to-end approval → outbox → relay → inbox | Verified as local integration test | In-process publisher only; no broker durability test |
| PostgreSQL disposable integration environment and Java CI | Implemented/tested | CI does not establish deployment or security certification |
| Signed JWT through servlet HTTP filter | Implemented for ephemeral RSA test keys (PR #50) | Test-only decoder verifies signature, issuer, audience and expiry; live Keycloak interoperability **unverified** |
| Secret, dependency, static-security scanning and workflow provenance | Implemented in hosted CI | Gitleaks, Trivy, SpotBugs/Find Security Bugs, pinned actions and curated evidence; not runtime certification |
| Production credentials, secret rotation and least-privilege IdP admin automation | Not established | Local lab bootstrap is not a production model |
| Production broker listener, retry operations, poison-event workflow | Not implemented | Require provenance/auth, tenant scope and operator controls |
| Real SCIM/app target provisioning and reconciliation | Not implemented | Never claim effective entitlement from `RECEIVED` |
| Kubernetes/multicloud deploy, observability SLOs, backup/restore, load tests | Not demonstrated | Milestone 05 candidates; define measurable acceptance gates first |
| Portfolio release evidence and interview workbook | Not completed | Scope-frozen [release gates](../release/portfolio-release-gates.md) RC-01 through RC-07 pending |

See [system overview](system-overview.md) for package layout and trust boundaries, and [Milestone 04 closeout](../milestones/04-closeout-evidence.md) for observed test counts and repository provenance.
