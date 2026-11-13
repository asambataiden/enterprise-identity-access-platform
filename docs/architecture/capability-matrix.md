# Capability matrix — implemented versus planned

**Snapshot:** `main` at `3350df1`, 2026-11-13. “Implemented” means code and tests exist in this repository, not production certification. Test-only and lab-only capabilities are labeled explicitly.

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
| Production credentials, secret rotation and least-privilege IdP admin automation | Not established | Local lab bootstrap is not a production model |
| Production broker listener, retry operations, poison-event workflow | Not implemented | Require provenance/auth, tenant scope and operator controls |
| Real SCIM/app target provisioning and reconciliation | Not implemented | Never claim effective entitlement from `RECEIVED` |
| Kubernetes/multicloud deploy, observability SLOs, backup/restore, load tests | Not demonstrated | Milestone 05 candidates; define measurable acceptance gates first |
| Attested portfolio release and interview workbook | Not completed | Milestone 06 |

See [system overview](system-overview.md) for package layout and trust boundaries, and [Milestone 04 closeout](../milestones/04-closeout-evidence.md) for observed test counts and repository provenance.
