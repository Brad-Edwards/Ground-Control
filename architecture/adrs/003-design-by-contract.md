# ADR-003: Design by Contract with icontract

## Status

Superseded by [ADR-013](013-java-spring-boot-rewrite.md)

## Date

2026-03-08

## Context

Ground Control aims to be high-assurance software. Design by contract (DbC) provides runtime-verified preconditions, postconditions, and invariants directly in production code, catching violations at the point of failure rather than in distant test suites.

icontract is a production dependency, and crosshair-tool is a dev dependency for static contract verification via symbolic execution.

## Decision

- icontract for runtime contract enforcement (preconditions, postconditions, class invariants)
- crosshair-tool for static/symbolic verification of contracts during development
- Contracts are part of the production code, not just tests

## Consequences

### Positive

- Bugs surface at the contract boundary with clear error messages, not as downstream corruption
- Contracts serve as executable documentation of function expectations
- crosshair can find contract violations without running the code, catching edge cases that tests miss

### Negative

- Runtime overhead from contract checking (can be disabled in production via `ICONTRACT_SLOW` if needed)
- Learning curve for developers unfamiliar with DbC

### Risks

- Over-contracting can make simple code verbose - apply contracts where invariants matter, not on trivial helpers
