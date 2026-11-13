# Milestone 05A-4 — HTTP authorization and no-write regression

## Objective
Prove that structurally invalid tenant identity claims are denied by the **actual Spring MVC security-filter and application authorization path**, rather than only by offline unit tests, and that rejected operations leave durable business state unchanged. This increment augments the existing Testcontainers-backed fixtures; it introduces no new production behavior, SQL migration, or authentication mode.

## Existing control chain
```mermaid
sequenceDiagram
    actor Attacker as API client
    participant F as Spring Security filters
    participant R as Tenant-bound actor resolver
    participant DB as PostgreSQL
    Attacker->>F: Valid test JWT principal and scope, but malformed tenant claim
    F->>R: Authorized HTTP route; resolve local actor
    R->>R: Validate issuer, subject, tenant UUID
    R-->>F: 403 Forbidden
    F-->>Attacker: Denied response
    Note over R,DB: No authorization lookup or business mutation for invalid claims
```

## Implemented security use cases

| Case | Target | Input | Expected status | Durable invariant |
| --- | --- | --- | --- | --- |
| IAM-HTTP-01 | POST access-request decision | Empty tenant ID claim | 403 | Request remains PENDING; zero decisions |
| IAM-HTTP-02 | POST access-request decision | Invalid UUID tenant ID | 403 | Request remains PENDING; zero decisions |
| IAM-HTTP-03 | POST access-request decision | Missing tenant ID | 403 | Request remains PENDING; zero decisions |
| IAM-HTTP-04 | GET quarantine details | Empty tenant ID | 403 | QUARANTINED state unchanged; zero review records |
| IAM-HTTP-05 | GET quarantine details | Invalid UUID tenant ID | 403 | QUARANTINED state unchanged; zero review records |
| IAM-HTTP-06 | GET quarantine details | Missing tenant ID | 403 | QUARANTINED state unchanged; zero review records |

## Test architecture / why the change is small
- Extend `ApprovalDecisionHttpIT` and `QuarantineInspectionHttpIT` rather than duplicating application test harness or provisioning fixture setup.
- Use the existing tenant/employee/permission seed data, Spring Security MockMvc configuration, and PostgreSQL 17 Testcontainers.
- Keep distinct negative authorization assertions and durable database assertions in a single test transaction-independent observation.
- Use `SecurityMockMvcRequestPostProcessors.jwt()` to create an authenticated testing principal **without performing a cryptographic signature check**. The separate `SignedJwtTrustValidationTest` exercises cryptographic verification; never misrepresent MockMvc JWT as a full integration with an external identity provider.

## Trust boundaries explained
1. Authentication: token is cryptographically trustworthy (different dedicated test layer).
2. HTTP scope authorization: Spring Security route accepts the needed scope.
3. Actor binding: subject and issuer must resolve to an active employee in exactly the claimed tenant and carry local permission.
4. Business operation: a resource is accessed only in the tenant context; unauthorized mutations leave the database unchanged.

**Negative use cases remain security-critical** even with successful SAST. Tools can identify suspect paths but do not prove business authorization invariants.

## Validation
```bash
mvn -B --no-transfer-progress -Dit.test=ApprovalDecisionHttpIT,QuarantineInspectionHttpIT failsafe:integration-test failsafe:verify
mvn -B --no-transfer-progress clean verify
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
mvn -B --no-transfer-progress -DskipTests package spotbugs:check
gh pr checks 41 --watch
```

Record exact PR commit SHA, test results and CI links before merging. PostgreSQL SSL/Testcontainers startup faults should be diagnosed independently; never weaken production TLS to address a flaky test environment.

## Gaps and future security evidence
These tests validate only malformed tenant claims for two HTTP paths. Future increments can cover full cross-tenant listing isolation, permission revocation racing an ongoing request, unauthorized request timing/error consistency, Keycloak end-to-end signature and audience enforcement, and audit-log fidelity. No penetration-test or comprehensive security certification is implied.

## Interview questions and answers
- **Why use both resolver unit tests and HTTP integration tests?** The former isolates fail-closed parsing; the latter exercises real routing, filter authorization, resolver wiring and durable state.
- **Why assert no mutation?** An HTTP 403 can hide an earlier side effect; verifying PENDING state and zero audit/review records detects that class of regression.
- **Why is valid scope insufficient?** OAuth scope does not establish tenant employee membership or local permission; explicit tenant-bound lookup is also necessary.
- **Why does a missing tenant claim fail closed?** Choosing an implicit tenant would enable cross-tenant authorization confusion and violate least privilege.
