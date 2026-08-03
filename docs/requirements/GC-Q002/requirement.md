---
id: GC-Q002
title: "Requirements Explorer"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-14T02:59:46.761333Z
updated_at: 2026-03-20T20:21:34.929501Z
---

# GC-Q002 — Requirements Explorer

## Statement

The web application shall provide a requirements explorer view with tabular listing, inline filtering by status, type, priority, and wave, free-text search, sortable columns, pagination, and the ability to view, create, edit, and transition requirements without leaving the view.

## Rationale

Requirements are the primary entity in Ground Control. An efficient explorer view is the workhorse interface for day-to-day requirements management — architects need to scan, filter, and update requirements frequently without context-switching between multiple screens or API calls.

## Traceability

- IMPLEMENTS → CODE_FILE `frontend/src/pages/requirement-detail.tsx` (Requirement detail page - View, edit, transition)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/requirements.tsx` (Requirements page - Explorer with filtering and search)
- IMPLEMENTS → CODE_FILE `frontend/src/components/ui/slide-panel.tsx` (SlidePanel reusable UI component)
- IMPLEMENTS → CODE_FILE `frontend/src/components/requirement-detail-panel.tsx` (Requirement detail panel (view/edit inline))
- IMPLEMENTS → CODE_FILE `frontend/src/main.css` (Slide panel animation keyframes)
- IMPLEMENTS → GITHUB_ISSUE `358` (GC-Q002: Requirements Explorer)
