---
id: GC-A009
title: "Filtering and Search"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:35.587048Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-A009 — Filtering and Search

## Statement

The system shall support filtering requirements by status, type, priority, wave, and free-text search across title and statement fields.

## Rationale

As the number of requirements grows, users need efficient ways to find and narrow down requirements. Core usability capability.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#296` (GC-A009: Filtering and Search)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementFilter.java` (RequirementFilter — priority field)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/RequirementSpecifications.java` (RequirementSpecifications — hasPriority spec)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (RequirementController — priority request param)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (Unit test — priority filter param)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementControllerIntegrationTest.java` (Integration test — filteredList_byPriority_returnsFiltered)
