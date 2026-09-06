---
id: GC-C004
title: "Orphan Detection"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:53.948479Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C004 — Orphan Detection

## Statement

The system shall detect orphan requirements — requirements with no incoming or outgoing relations to other requirements — and report them.

## Rationale

Orphan requirements indicate either missing context (they should be related to something) or abandoned work. Surfacing them prevents requirements from being silently forgotten.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#318` (Bug: AnalysisService N+1 query pattern in findOrphans() and findCoverageGaps())
- IMPLEMENTS → GITHUB_ISSUE `337` (GC-C004: Orphan Detection)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - findOrphans)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (AnalysisController - orphan detection endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest - findOrphans unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (AnalysisIntegrationTest - orphan detection integration test)
