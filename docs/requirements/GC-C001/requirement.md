---
id: GC-C001
title: "Cycle Detection"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:45.489258Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C001 — Cycle Detection

## Statement

The system shall detect cycles in the requirement relation DAG and report the cycle members, identifying which requirements and relation types form the cycle.

## Rationale

Cycles in a dependency graph indicate logical contradictions or modeling errors. Early detection prevents downstream analysis from entering infinite loops or producing incorrect results.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#307` (GC-C001: Cycle Detection)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/CycleEdge.java` (CycleEdge domain record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/CycleResult.java` (CycleResult domain record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService cycle detection with edge types)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/CycleResponse.java` (CycleResponse API DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (AnalysisController cycles endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisService cycle detection unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AnalysisControllerTest.java` (AnalysisController cycle detection API test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/CycleDetectionPropertyTest.java` (Cycle detection property-based tests)
