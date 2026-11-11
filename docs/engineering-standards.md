# Engineering workflow

1. Create feature branches from `main`; use Conventional Commits, small PRs and explicit approval gates.
2. Define the behavior and negative security cases before writing the endpoint.
3. Keep tests deterministic: unit vs HTTP vs PostgreSQL/Keycloak integration scopes must be labeled.
4. Record exact JDK/Spring Boot/dependency versions and baseline git SHA in evidence, never forecast successful results.
5. Minimize and review security grants. Secrets never go into source, CLI pasted logs or markdown samples.
6. Automation first for repeatable verification; no automatic deletion/provisioning without explicit opt-in and rollback.
7. Update README, diagrams, ADRs, and test plan in the same change as the affected capability.
8. Keep learning documentation in a separate private repository only when actually needed; do not create curriculum infrastructure ahead of the project.

**Milestone 00 runtime only**: health endpoint anonymous, other HTTP routes deny. Future authorization will require signed JWT validation, tenant and resource scoping and separation of duties.
