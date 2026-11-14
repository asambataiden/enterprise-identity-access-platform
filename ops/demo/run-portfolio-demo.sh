#!/usr/bin/env bash
# RC-B: selected deterministic IAM portfolio demonstration; no live Keycloak.
# Runs existing acceptance tests against Testcontainers disposable PostgreSQL.
set -Eeuo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$root"

for required in java mvn docker git; do
  if ! command -v "$required" >/dev/null 2>&1; then
    printf 'FAIL: required command not found: %s\n' "$required" >&2
    exit 2
  fi
done

if ! docker info >/dev/null 2>&1; then
  printf 'FAIL: Docker engine unavailable; Testcontainers needs Docker.\n' >&2
  exit 2
fi

printf 'RC-B portfolio demonstration — isolated integration tests\n'
printf 'Commit: %s\n' "$(git rev-parse HEAD)"
printf 'Modules: API request; approval; simulated target; outbox/inbox; quarantine; signed JWT HTTP\n'
printf 'No live Keycloak, no real identities, no external entitlement grants.\n'
printf 'Each integration test owns an independent disposable fixture; this is NOT one continuous live API session.\n'

# Surefire requires an existing *Test class when -Dtest is provided.
# Failsafe runs only the curated *IT classes listed below.
mvn -B --no-transfer-progress \
  -Dtest=SignedJwtTrustValidationTest \
  -Dit.test=AccessRequestPersistenceIT,ApprovalDecisionHttpIT,MockProvisioningOrchestratorIT,OutboxInboxEndToEndIT,QuarantineInspectionHttpIT,SignedJwtHttpFilterIT \
  verify

printf 'PASS: curated IAM portfolio demo suite completed.\n'
printf 'LIMIT: synthetic data and mock target; successful broker/IdP/third-party provisioning not demonstrated.\n'
