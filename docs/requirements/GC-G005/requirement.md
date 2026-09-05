---
id: GC-G005
title: "Graph Visualization Data"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-13T23:13:48.917025Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-G005 — Graph Visualization Data

## Statement

The system shall provide graph visualization data (nodes and edges with metadata) in a format suitable for UI rendering of requirement relationship diagrams.

## Rationale

Visual representation of the requirement graph aids comprehension of complex dependency structures. Graph data must be structured for frontend rendering libraries.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `366` (GC-G005: Graph Visualization Data)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `frontend/src/pages/graph.tsx` (Graph page - Interactive graph visualization)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphController.java` (Graph visualization endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphVisualizationResponse.java` (Graph visualization response DTO)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GraphControllerTest.java` (Graph visualization endpoint tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (Graph visualization service tests)
