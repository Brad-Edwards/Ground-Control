---
id: GC-Q002
title: "Requirements Explorer"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-14T02:59:46.761333Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-Q002 — Requirements Explorer

## Statement

The web application shall provide a requirements explorer view with tabular listing, inline filtering by status, type, priority, and wave, free-text search, sortable columns, pagination, and the ability to view, create, edit, and transition requirements without leaving the view.

## Rationale

Requirements are the primary entity in Ground Control. An efficient explorer view is the workhorse interface for day-to-day requirements management — architects need to scan, filter, and update requirements frequently without context-switching between multiple screens or API calls.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `358` (GC-Q002: Requirements Explorer)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `frontend/src/pages/requirement-detail.tsx` (Requirement detail page - View, edit, transition)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/requirements.tsx` (Requirements page - Explorer with filtering and search)
- IMPLEMENTS → CODE_FILE `frontend/src/components/ui/slide-panel.tsx` (SlidePanel reusable UI component)
- IMPLEMENTS → CODE_FILE `frontend/src/components/requirement-detail-panel.tsx` (Requirement detail panel (view/edit inline))
- IMPLEMENTS → CODE_FILE `frontend/src/main.css` (Slide panel animation keyframes)
