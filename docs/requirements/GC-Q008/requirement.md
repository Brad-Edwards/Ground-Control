---
id: GC-Q008
title: "Project Switcher"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-15T19:55:01.066760Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-Q008 — Project Switcher

## Statement

The web application shall provide a project selector that sets the active project context for all views. The selector shall be persistently visible in the application header, display the current project name, and allow switching between all projects the user has access to. The selected project shall persist across page navigation and browser refresh (via URL path prefix or local storage). All data views — requirement lists, dependency graphs, dashboards, analysis results — shall scope to the active project without requiring per-view filtering.

## Rationale

GC-A013 introduces project scoping at the backend, but without a UI mechanism to select and switch projects, users must manually pass project identifiers in every query. A persistent project selector is the standard UX pattern (GitHub repo switcher, Jira project picker, Linear workspace selector) that makes multi-project usable. Every other Q-series web requirement implicitly assumes the user is working within a single project context — this requirement makes that context explicit and switchable.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#331` (GC-Q008: Project Switcher)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `frontend/src/routes.tsx` (URL-driven route structure with /p/:projectId prefix)
- IMPLEMENTS → CODE_FILE `frontend/src/contexts/project-context.tsx` (ProjectProvider derives activeProject from URL param)
- IMPLEMENTS → CODE_FILE `frontend/src/components/project-switcher.tsx` (Project switcher navigates with sub-path preservation)
- IMPLEMENTS → CODE_FILE `frontend/src/components/layout/app-layout.tsx` (AppLayout with project-scoped nav links)
- IMPLEMENTS → CODE_FILE `frontend/src/app.tsx` (App component restructured for URL-driven project context)
