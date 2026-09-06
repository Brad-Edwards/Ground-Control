---
id: GC-G006
title: "Graph Entity Type Filtering"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-26T15:08:47.941780Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-G006 — Graph Entity Type Filtering

## Statement

The system shall support filtering graph visualizations and graph queries by entity type, allowing users to include or exclude specific entity types (requirements, documents, sections, risks, controls, etc.) from graph results. Filters shall apply to both the visualization data endpoint and the subgraph extraction endpoint.

## Rationale

As the traceability graph grows beyond requirements to include documents, risks, controls, and other entity types, the graph becomes noisy. Users need to focus on specific entity types (for example, show only requirements and their relations, or show documents with their requirement associations) without seeing unrelated entities. Without filtering, the graph becomes unusable as entity diversity increases.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphController.java` (GraphController - entityTypes filter on visualization and subgraph)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphVisualizationNodeResponse.java` (GraphVisualizationNodeResponse - entityType field on nodes)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GraphControllerTest.java` (Graph entity-type filtering tests on the visualization and subgraph/traversal endpoints (entityTypes filter))
