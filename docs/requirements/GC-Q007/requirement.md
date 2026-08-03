---
id: GC-Q007
title: "DAG-Derived Work Order"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T17:56:50.972556Z
updated_at: 2026-03-19T21:10:22.473069Z
---

# GC-Q007 — DAG-Derived Work Order

## Statement

The system shall provide an API that returns a topologically-sorted work order derived from the requirements DAG, grouping requirements by wave and ordering within each wave by dependency topology and MoSCoW priority. The output shall identify which requirements are currently unblocked (all dependencies satisfied), which are blocked and by what, and which have no dependency constraints. The API shall be exposed via both REST and MCP tools.

## Rationale

The requirements DAG already encodes wave assignments, DEPENDS_ON/REFINES edges, and MoSCoW priorities — all the inputs needed to derive a valid execution sequence. Surfacing this as a first-class API enables agents to autonomously select the next highest-value unblocked work item and enables humans to visualize the critical path without manually tracing dependencies.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService.getWorkOrder() — work order algorithm)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphAlgorithms.java` (GraphAlgorithms.topologicalSort() — Kahn's algorithm)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (REST endpoint GET /api/v1/analysis/work-order)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_get_work_order MCP tool)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest.GetWorkOrder — 6 unit tests)
- IMPLEMENTS → GITHUB_ISSUE `#232` (GC-Q007: DAG-Derived Work Order)
