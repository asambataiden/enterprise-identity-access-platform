# IAM platform — security threat model and verification boundaries

**Baseline:** `3350df1`, 2026-11-13. This is a threat-driven engineering checklist, **not** an audit, certification or security test report.

## Trust boundaries

1. **Client → HTTP API:** signed JWT issuer, audience and expiry validation; path-specific OAuth scope. `POST` access requests, decisions and read-only quarantine inspection have separate route-level gates. Anonymous health is deliberate.
2. **JWT identity → local business actor:** signed token alone does not grant tenant membership or permissions; resolve issuer/subject/tenant to an active local employee and check the appropriate local permission.
3. **Application → PostgreSQL:** tenant-scoped key/foreign-key constraints, transactionally coupled audit/outbox and inbox/projection writes; avoid trusting caller-supplied tenant/requester identity.
4. **Provisioning → target adapter:** current adapter is loopback mock-only; no real entitlement. Real integrations require scoped service credentials and read-before-write/idempotency strategies.
5. **Outbox → consumer:** existing bridge is explicitly invoked in tests; there is no authenticated production broker listener. An eventual transport requires provenance, schema/tenant validation, retries, poisoning controls and bounded resource use.
6. **Developer/CI → credentials and evidence:** use synthetic identities, redacted logs, secret scans and restricted service accounts; Keycloak local lab bootstrap is not a production-admin pattern.

## Security verification matrix

| Threat / misuse | In-repository mitigation or test scope | Remaining gap |
| --- | --- | --- |
| Forged/expired/mis-audienced access token | Spring JWT resource-server configuration and signed-token negative tests | Production IdP configuration, rotation and environment policy |
| Token with scope but without local authorization | Request/decision/reviewer actor resolution and permission checks | Continuous adversarial tenant fixture tests |
| Tenant spoofing and cross-tenant record access | Server-derived actor and tenant-scoped DB integrity | Authorization review for any new endpoint |
| Request or decision replay | Idempotent request intent, approval state checks and transactional audit | Rate limits and production traffic patterns |
| Duplicate event publication | Stable event identity, V14 inbox transaction and duplicate/conflict tests | Broker delivery authentication, retention and external side-effect idempotency |
| Outbox worker crashes, stale lease | V13 lease token fencing, retry and dead-letter state | Automated alerting, controlled re-drive and operations playbook |
| Uncertain target state | Simulation, observation and quarantine evidence | Real target readback and reconciliation not implemented |
| Secrets and sensitive logs | Review standards and synthetic local lab | Automated secret scanning and runtime secret store not yet verified |
| Deployment/container compromise | Deny-by-default and local Docker test environment | Threat-driven hardening and repeatable deployment in Milestone 05 |

## Required Milestone 05 gates

Define ownership, severity, evidence link and reproducible negative test for each production-facing control before claiming readiness. Include TLS configuration, least-privilege client permissions, dependency/secrets scanning, authorization regression, redacted structured logs, monitored outbox lag/dead letters, and a restoration drill where applicable.

References: [OWASP ASVS](https://asvs.owasp.org/) and [OAuth 2.0 Security Best Current Practice (RFC 9700)](https://www.rfc-editor.org/rfc/rfc9700.html). A reference to a standard is **not** a claim of verified conformance.
