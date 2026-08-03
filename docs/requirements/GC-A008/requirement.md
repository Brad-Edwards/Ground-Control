---
id: GC-A008
title: "Bulk Status Transitions"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:10:33.367148Z
updated_at: 2026-03-14T23:56:04.342694Z
---

# GC-A008 — Bulk Status Transitions

## Statement

The system shall support bulk status transitions for multiple requirements in a single operation, applying the same state machine rules to each.

## Rationale

Large-scale reorganizations (for example, activating an entire wave of requirements) should not require one-at-a-time manual transitions.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/BulkTransitionResult.java` (BulkTransitionResult domain record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java` (RequirementService.bulkTransitionStatus())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (RequirementController.bulkTransitionStatus() endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java` (RequirementServiceTest.BulkTransitionStatus (3 tests))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (RequirementControllerTest.BulkTransitionStatus (2 tests))
- IMPLEMENTS → GITHUB_ISSUE `#292` (GC-A008: Bulk Status Transitions)
