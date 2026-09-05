---
id: GC-Q015
title: "Console Shell and Design System"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 8
created_at: 2026-07-02T22:18:04.608718Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-Q015 — Console Shell and Design System

## Statement

The web application shall provide a SaaS-grade console shell and a documented design system.

(a) Shell. A coherent product shell shall provide authenticated session UX inside the SPA (sign-in state, current-user menu, session-expiry handling, sign-out), global navigation with project/context switching (subsuming GC-Q008), a notification surface, and responsive layout — replacing the current unauthenticated-SPA-plus-separate-login-page arrangement.

(b) Design system. A documented design system (design tokens; typography, color, and spacing scales; a reusable component library with usage guidance; interaction patterns for tables, forms, panels, and empty/error/loading states) shall be the required construction material for console pages; new workspaces compose from the system rather than hand-rolling UI.

(c) Migration. Existing workspaces (requirements, traceability, graph, analysis, GRC workspaces, workflow runs, admin) shall be migrated to or made visually consistent with the shell and design system without loss of function.

(d) Contract-generated client. The console shall consume the generated API client (GC-O014); hand-mirrored API type definitions shall be removed.

(e) Accessibility and quality. The shell and design-system components shall support keyboard navigation and WCAG 2.1 AA contrast, and design-system components shall carry component tests.

## Rationale

GC-Q001 built a functional internal console; running the entire development workflow for all projects through it — including human gate actions by multiple users and groups (GC-P024) — raises the bar to a SaaS-grade product surface. The current SPA has no in-app auth UX, a hand-rolled component set, and hand-mirrored API types. A design system makes the workflow operations console (GC-Q016) and future tenant surfaces (GC-Q014) composable rather than bespoke.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `1283` (GC-Q015: console shell and design system)

## Historical traceability

Links below named artifacts the #1500 re-platform deleted. They are kept for
provenance and are outside the parsed `## Traceability` section, so no tool reads
them as live evidence. Do not infer current implementation from them.

- IMPLEMENTS → DOCUMENTATION `docs/frontend/design-system.md` (Design-system usage documentation (clause b))
- IMPLEMENTS → CODE_FILE `tools/contracts/generate-contracts.mjs` (Generated-client precise typing (clause d))
- IMPLEMENTS → CODE_FILE `frontend/src/components/layout/app-shell.tsx` (AppShell — grouped responsive shell (clause a))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/session/SessionController.java` (SessionController — current-principal read (clause a))
- IMPLEMENTS → CODE_FILE `frontend/src/hooks/use-session.ts` (useSession — session read hook (clause a))
- IMPLEMENTS → CODE_FILE `frontend/src/components/ui/user-menu.tsx` (UserMenu — signed-in principal + sign-out (clause a))
- IMPLEMENTS → CODE_FILE `frontend/src/main.css` (Semantic design tokens (clause b))
- IMPLEMENTS → CODE_FILE `frontend/src/components/ui/button.tsx` (Design-system component library (clause b))
- IMPLEMENTS → CODE_FILE `frontend/src/pages/dashboard.tsx` (Workspace migration onto shell + tokens (clause c))
- IMPLEMENTS → CODE_FILE `frontend/src/hooks/use-projects.ts` (Hand-mirrored ProjectResponse removed (clause d))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/SessionControllerTest.java` (SessionController @WebMvcTest slice (clause a/e))
- TESTS → TEST `frontend/src/components/layout/__tests__/app-shell.test.tsx` (AppShell component test — grouped nav + admin gating (clause e))
- TESTS → TEST `frontend/src/components/ui/__tests__/user-menu.test.tsx` (UserMenu component test — principal + admin cue gating (clause e))
- TESTS → TEST `frontend/src/components/ui/__tests__/badge.test.tsx` (Semantic badge component test (clause b/e))
- TESTS → TEST `frontend/src/components/ui/__tests__/notification-center.test.tsx` (NotificationCenter component test (clause a/e))
