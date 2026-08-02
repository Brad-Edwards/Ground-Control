---
id: GC-G003
title: "Subgraph Extraction"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:13:42.919387Z
updated_at: 2026-03-22T00:13:13.065549Z
---

# GC-G003 — Subgraph Extraction

## Statement

The system shall support extracting subgraphs: given a set of root requirements, return all transitively reachable requirements and their relations as a self-contained graph.

## Rationale

Subgraph extraction enables focused analysis on a specific area of the requirement space without the noise of unrelated requirements.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphAlgorithms.java` (GraphAlgorithms.findReachableFromMultiple())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService.extractSubgraph())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphController.java` (GET /api/v1/graph/subgraph endpoint)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool gc_extract_subgraph)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GraphControllerTest.java` (GraphControllerTest.Subgraph)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest.ExtractSubgraph)
- IMPLEMENTS → GITHUB_ISSUE `371` (GC-G003: Subgraph Extraction)
