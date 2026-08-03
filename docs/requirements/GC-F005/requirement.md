---
id: GC-F005
title: "Verifier Adapter Interface"
status: ACTIVE
type: INTERFACE
priority: MUST
wave: 3
created_at: 2026-03-13T23:13:31.706095Z
updated_at: 2026-04-08T17:54:17.033058Z
---

# GC-F005 — Verifier Adapter Interface

## Statement

The system shall define a pluggable verifier adapter interface enabling integration with multiple verification tools including OpenJML, TLA+/TLC, OPA/Rego, Frama-C, and manual review processes.

## Rationale

ADR-014 mandates a prover-agnostic architecture. No single verifier covers all artifact types. The adapter pattern enables adding new verifiers without modifying core verification logic.

## Traceability

- DOCUMENTS → ADR `ADR-014` (Pluggable Verification Architecture)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/VerifierAdapter.java` (VerifierAdapter interface)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/VerificationRequest.java` (VerificationRequest record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/VerificationOutcome.java` (VerificationOutcome record)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/VerifierAdapterContractTest.java` (VerifierAdapter contract tests)
