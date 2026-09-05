---
id: GC-Q005
title: "Interactive Dependency Graph"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T02:59:51.268004Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-Q005 — Interactive Dependency Graph

## Statement

The web application shall provide an interactive graph visualization of requirement relations supporting pan, zoom, node selection, neighborhood highlighting, and filtering by series, priority, status, and wave. The visualization shall support multiple DAG layout modes with wave-ordered rendering. Color coding shall be switchable across dimensions. The legend shall show per-group counts and support click-to-filter. Deployable as a containerized service consuming the REST API.

## Rationale

Requirement dependency graphs are inherently spatial — cycles, clusters, orphans, and critical paths are patterns that humans recognize visually but struggle to identify in tabular or textual output. An interactive graph turns analysis results from abstract data into actionable insight. A prototype using Cytoscape.js with dagre layout validated this approach with the full Ground Control dataset (170+ nodes, 100+ edges), confirming lightweight browser-based rendering is sufficient for this view.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `364` (GC-Q005: Interactive Dependency Graph)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `frontend/src/pages/graph.tsx` (Interactive dependency graph page (React/Cytoscape.js))
- TESTS → TEST `frontend/src/lib/graph-constants.test.ts` (Graph constants unit tests (getSeries, getNodeColor, getColorMap))
