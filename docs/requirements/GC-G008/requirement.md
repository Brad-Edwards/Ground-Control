---
id: GC-G008
title: "Mixed-Entity Graph Traversal and Visualization"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:19.656990Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-G008 — Mixed-Entity Graph Traversal and Visualization

## Statement

The system shall support graph traversal, path finding, subgraph extraction, and visualization across mixed entity types including requirements, operational assets, risk scenarios, controls, findings, audits, evidence, and external artifacts. These capabilities shall not be limited to requirement-only graphs.

## Rationale

Once Ground Control becomes a graph-native factory, graph analysis has to work across the whole operational and assurance model rather than stopping at requirement nodes. Mixed-entity traversal is how agents and humans will perform impact, coverage, and assurance analysis.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#728` (GC-G008: Mixed-Entity Graph Traversal and Visualization)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphController.java` (GraphController — mixed-entity graph REST endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/MixedGraphService.java` (MixedGraphService — visualization, traversal, paths, subgraph orchestration)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/MixedGraphClient.java` (MixedGraphClient — projection contract for the mixed graph)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/age/AgeGraphService.java` (AgeGraphService — Apache AGE adapter with JPA fallback (ADR-032))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/GraphTraversalLimits.java` (GraphTraversalLimits — centralized traversal bounds)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEntityType.java` (GraphEntityType — 21-member mixed-entity enum)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/graph.tsx` (Graph page — Cytoscape mixed-entity visualization, tooltip + stats)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GraphControllerTest.java` (GraphControllerTest — controller slice unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/MixedGraphServiceTest.java` (MixedGraphServiceTest — service unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/AgeGraphServiceTest.java` (AgeGraphServiceTest — AGE adapter unit tests)
- TESTS → TEST `frontend/src/pages/__tests__/graph-tooltips.test.tsx` (graph-tooltips.test.tsx — tooltip behavioural coverage for all GraphEntityType members)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/RequirementGraphProjectionContributor.java` (Requirement-to-artifact mixed-entity graph projection)
