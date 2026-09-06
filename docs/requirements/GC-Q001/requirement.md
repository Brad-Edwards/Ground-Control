---
id: GC-Q001
title: "Interactive Web Application"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-14T02:59:44.384111Z
updated_at: 2026-03-18T07:20:23.732437Z
---

# GC-Q001 — Interactive Web Application

## Statement

The system shall provide an interactive web application that enables architects and developers to browse, author, and audit requirements, traceability, and analysis results through a visual interface consuming the REST API.

## Rationale

Ground Control currently operates as a headless system accessible only through REST API and MCP tools. While MCP serves AI agents well, human users — architects reviewing traceability, developers checking coverage, leads planning waves — need a visual interface for efficient navigation, pattern recognition, and bulk operations that are impractical through API calls alone.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/017-interactive-web-application.md` (ADR-017: Interactive Web Application)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#345` (GC-Q001: Interactive Web Application)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `frontend/src/app.tsx` (App - Interactive web application shell)
- IMPLEMENTS → CODE_FILE `frontend/src/routes.tsx` (Routes - Web application routing)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/requirements.tsx` (Requirements list page — browse, filter, sort, create, bulk edit)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/requirement-detail.tsx` (Requirement detail — edit, relations, traceability, history, impact)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/analysis.tsx` (Analysis page — cycles, orphans, coverage, cross-wave, consistency, completeness)
- IMPLEMENTS → CODE_FILE `frontend/src/lib/api-client.ts` (API client — REST API integration layer)
