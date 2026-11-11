# ADR-0001 — Begin as a modular monolith

- **Status:** Accepted for Milestone 00
- **Date:** 2026-11-11

## Context

The capstone must prove correctness of access governance and cross-tenant security before building a distributed operational footprint.

## Decision

Start with one deployable Spring Boot service. Structure packages by business capability and inside them by API/application/domain/infrastructure when those distinctions materially improve ownership and tests. Do not introduce a separate service or shared library without a justified independent lifecycle.

## Options

- Separate microservices from day one: independent scaling, but premature network trust and consistency complexity.
- One application with unstructured packages: quick to write, difficult to protect business boundaries.

## Consequences

Simpler transaction consistency and repeatable local tests. Independent deployment or scale of entitlement governance is deferred. Extract services only with measured operational or ownership needs and an explicit event contract.
