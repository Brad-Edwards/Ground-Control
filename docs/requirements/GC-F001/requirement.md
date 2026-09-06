---
id: GC-F001
title: "Verification Result Storage"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 3
created_at: 2026-03-13T23:13:20.142513Z
updated_at: 2026-04-06T07:46:07.473428Z
---

# GC-F001 — Verification Result Storage

## Statement

The system shall store verification results from any prover or verifier in a common schema, capturing: the requirement verified, the verifier used, the result (pass/fail/error/inconclusive), evidence artifacts, and timestamp.

## Rationale

ADR-014 specifies a prover-agnostic verification architecture. A common result schema enables uniform reporting regardless of whether verification comes from OpenJML, TLA+, OPA, or manual review.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool registrations for verification results)
- IMPLEMENTS → GITHUB_ISSUE `497` (GC-F001: Verification Result Storage)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/model/VerificationResult.java` (VerificationResult entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/state/VerificationStatus.java` (VerificationStatus enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/state/AssuranceLevel.java` (AssuranceLevel enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/repository/VerificationResultRepository.java` (VerificationResultRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/VerificationResultService.java` (VerificationResultService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/verification/VerificationResultController.java` (VerificationResultController)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V049__create_verification_result.sql` (Flyway migration V049)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/VerificationResultServiceTest.java` (VerificationResultService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/VerificationResultControllerTest.java` (VerificationResultController unit tests)
