# ADR-0002 — Use an established external identity provider

- **Status:** Accepted direction; integration not implemented in Milestone 00
- **Date:** 2026-11-11

## Context

Building password storage, MFA, token issuance and federation from scratch is unnecessary and increases identity security risk.

## Decision

Delegate authentication, OAuth2/OIDC authorization-server duties and MFA to Keycloak in development. The Java service will be a resource server when integration is enabled, verify signed tokens, issuer, audience, expiry and required authorities, then enforce tenant/resource-aware authorization in application logic. Treat identity-provider admin APIs as privileged integrations. Separate business entitlement grants from Keycloak's broad realm roles.

## Consequences

Avoid custom authentication protocol code but accept external-IdP availability, lifecycle, mapping, configuration, JWK rotation and operational trust responsibilities. Never trust arbitrary JWT claims without validation and mapping. Do not equate realm role possession with unconditional business-resource access.
