# Security threat model — Milestone 00

**Status:** initial threat hypothesis, not a security assessment or certification.

| Threat | Trust boundary | Planned mitigation | Test evidence required |
| --- | --- | --- | --- |
| Spoofed or unvalidated token | Client → API | Keycloak signed JWT, issuer/audience/expiry verification | Invalid signature, audience, expiry |
| Cross-tenant access | Actor → employee/request | Validated tenant context + scoped data access | Negative integration tests |
| Self-approval | Requester → approval | Enforce different approver + eligibility policy | Self-approval rejection |
| Privilege escalation | Requested role → grant | Allowlisted role catalog + least privilege | Unsupported/high-risk roles denied |
| Replay / duplicate provision | Client/retry → database/outbox | Tenant-scoped idempotency and consumer dedup | Concurrent and retry tests |
| Unauthorized IdP administration | Service → Keycloak | Scoped service account, no shared admin credentials | Privilege and config review |
| Audit manipulation | Actor → evidence store | Append-only protected records, separation of duties | Mutation rejection tests |
| Secret leakage | CI/logging → repository | No secrets in Git; dedicated secret injection | Secret scans / review |

**Current implemented control:** a deny-by-default HTTP SecurityFilterChain with only Actuator health permitted. It is an early safety posture, **not** complete JWT or business authorization.

References: [OWASP ASVS 5.0](https://asvs.owasp.org/) and [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html). Map individual verification requirements to tests as security capabilities are implemented.
