# Delivery roadmap — evidence-gated

| Milestone | Deliverable | Exit gate |
| --- | --- | --- |
| 00 | Repository, UC-001, ADRs, threat model, Java baseline, deny-by-default security and CI | `mvn clean verify` + green CI; no IAM API claims |
| 01 | PostgreSQL/Flyway, tenant/app/subject models, access-request state and idempotency | Real Testcontainers integration tests incl. concurrent and cross-tenant rejection |
| 02 | Keycloak OIDC resource server, JWT trust and real admin identity mapping | Signed-token HTTP security tests incl. negative cases |
| 03 | Approval workflow, separation of duties, delegated permissions | UC-001 authorization and state-machine acceptance suite |
| 04 | Controlled provisioning, reconciliation, audit and outbox | Retry/recovery, unauthorized provisioning tests |
| 05 | DevSecOps, deployment and observability | Reproducible deployment, scans, hardening and operational evidence |
| 06 | Portfolio release, architecture and interview workbook | Versioned release and linked source-specific test results |

**Not on critical path:** broad SCIM/federation/multicloud and generic policy engine until a tested user story justifies them.
