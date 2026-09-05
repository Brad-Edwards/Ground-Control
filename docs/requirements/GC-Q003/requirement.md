---
id: GC-Q003
title: "Traceability Matrix"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-14T02:59:47.710598Z
updated_at: 2026-06-14T05:06:36.281870Z
---

# GC-Q003 — Traceability Matrix

## Statement

The web application shall provide a traceability matrix view displaying requirements on one axis and linked artifacts (code files, tests, ADRs, specifications) on the other, with visual indicators for link type, coverage completeness, and gaps. The matrix shall be filterable by wave, status, and link type.

## Rationale

The traceability matrix is the canonical audit artifact in requirements engineering. It answers 'is every requirement implemented and tested?' at a glance — a question that is critical for release decisions, compliance reviews, and architecture assessments, and nearly impossible to answer by scanning individual API responses.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_traceability_matrix MCP tool)
- DOCUMENTS → GITHUB_ISSUE `#689` (GC-Q003: Traceability Matrix)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (GET /api/v1/requirements/matrix endpoint)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/traceability-matrix.tsx` (Traceability Matrix page)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (@WebMvcTest matrix endpoint cases)
- TESTS → TEST `frontend/src/pages/__tests__/traceability-matrix.test.tsx` (Matrix page vitest cases)
