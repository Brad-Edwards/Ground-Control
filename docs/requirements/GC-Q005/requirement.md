---
id: GC-Q005
title: "Interactive Dependency Graph"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T02:59:51.268004Z
updated_at: 2026-03-21T06:58:05.621939Z
---

# GC-Q005 — Interactive Dependency Graph

## Statement

The web application shall provide an interactive graph visualization of requirement relations supporting pan, zoom, node selection, neighborhood highlighting, and filtering by series, priority, status, and wave. The visualization shall support multiple DAG layout modes with wave-ordered rendering. Color coding shall be switchable across dimensions. The legend shall show per-group counts and support click-to-filter. Deployable as a containerized service consuming the REST API.

## Rationale

Requirement dependency graphs are inherently spatial — cycles, clusters, orphans, and critical paths are patterns that humans recognize visually but struggle to identify in tabular or textual output. An interactive graph turns analysis results from abstract data into actionable insight. A prototype using Cytoscape.js with dagre layout validated this approach with the full Ground Control dataset (170+ nodes, 100+ edges), confirming lightweight browser-based rendering is sufficient for this view.

## Traceability

- IMPLEMENTS → CODE_FILE `frontend/src/pages/graph.tsx` (Interactive dependency graph page (React/Cytoscape.js))
- TESTS → TEST `frontend/src/lib/graph-constants.test.ts` (Graph constants unit tests (getSeries, getNodeColor, getColorMap))
- IMPLEMENTS → GITHUB_ISSUE `364` (GC-Q005: Interactive Dependency Graph)
