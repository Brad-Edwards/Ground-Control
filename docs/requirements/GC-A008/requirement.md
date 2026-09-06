---
id: GC-A008
title: "Bulk Status Transitions"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:10:33.367148Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-A008 — Bulk Status Transitions

## Statement

The system shall support bulk status transitions for multiple requirements in a single operation, applying the same state machine rules to each.

## Rationale

Large-scale reorganizations (for example, activating an entire wave of requirements) should not require one-at-a-time manual transitions.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#292` (GC-A008: Bulk Status Transitions)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/BulkTransitionResult.java` (BulkTransitionResult domain record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java` (RequirementService.bulkTransitionStatus())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (RequirementController.bulkTransitionStatus() endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java` (RequirementServiceTest.BulkTransitionStatus (3 tests))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (RequirementControllerTest.BulkTransitionStatus (2 tests))
