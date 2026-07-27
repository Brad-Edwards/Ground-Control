# Console Information Architecture and Design-System Foundations

## Status

Design reference for issue #1274. This document is the construction input for
GC-Q015 / issue #1283 (console shell and design system) and GC-Q016 / issue
#1284 (workflow operations console).

**Re-scoped 2026-07-13 (issue #1384).** This document was written against the
Temporal orchestration lane, which issue #1359 removed: no worker, namespace,
task queue, operator-signal API, or workflow-execution control surface exists,
and ADR-028, ADR-081, and ADR-088 are superseded. A forward-looking design
reference is a construction input, not a historical record, so the sections
that assumed that machinery are not preserved for posterity - they are
corrected. What survives is a **read and reporting** console: the GitHub issue
thread as the durable workflow record (ADR-029) and the ADR-061 workflow-run
telemetry model as the queryable projection over it.

It is not an implementation plan for new routes, controllers, or migrations.
ADR-085 decides the identity-administration surface. No workflow-control
contract exists today.

## Binding Inputs

- ADR-017: the frontend is a React / TypeScript SPA consuming the REST API.
- ADR-037: browser users authenticate through the session-cookie and CSRF
  model; the login bundle stays separate from the authenticated SPA shell.
- ADR-029: the GitHub issue thread is the durable workflow record. The console
  reads and links to it; it is not a second record of authority.
- ADR-061: workflow-run telemetry is a read-model - a correlation/projection
  surface over runs, phase events, and economics. It explicitly never drives
  execution, retries, signals, or gate completion, and the console must not
  turn it into a control plane by rendering it as one.
- ADR-085: identity administration is a domain model for users, groups, roles,
  permissions, and project access; it is not SaaS tenancy.
- `architecture/notes/console-ia-design-system-preflight.md`: guardrails for
  this design issue.
- Existing frontend surfaces under `frontend/src/routes.tsx`,
  `frontend/src/components/layout/app-layout.tsx`, `frontend/src/components`,
  and `frontend/src/pages`.

## Design Goals

1. Give the console a stable information architecture that handles the current
   project workspaces plus workflow reporting and identity administration.
2. Define a design-system foundation that future pages compose from instead of
   creating page-local UI patterns.
3. Specify authenticated-session UX inside the product shell while preserving
   ADR-037's standalone login bundle and backend security contracts.
4. Define run-observation and durable-record reading patterns for GC-Q016
   against the telemetry read-model that exists, without inventing a workflow
   control surface that does not.
5. Keep project scoping, identity administration, workflow reporting, and
   future tenancy as separate concepts.

## Information Architecture

### Scope Model

The shell has two scopes:

| Scope | URL shape | Purpose |
|-------|-----------|---------|
| Global/operator | `/projects`, future `/operations`, future `/identity` | Work that spans projects or administers principals and access. |
| Project | `/p/:projectId/...` | Work inside one Ground Control project. |

`Project` is the current product scope. It is not a tenant or organization.
Future tenant/workspace surfaces may add a higher scope, but GC-Q015 and
GC-Q016 must not imply that `Project` already provides SaaS isolation.

### Navigation Groups

The shell should replace the current flat top navigation with grouped
navigation that remains usable as the console grows. The exact rendering can
be a left rail on desktop and a drawer or compact menu on small screens, but
the grouping is stable.

| Group | Items | Scope |
|-------|-------|-------|
| Switcher | Project switcher, current project name, optional recent projects | Global and project |
| Overview | Dashboard | Project |
| Requirements | Requirements, Requirement Detail | Project |
| Traceability and Verification | Traceability Matrix, Test Runs, Test Runner | Project |
| Graph and Analysis | Graph, Analysis | Project |
| Assurance | Portfolio, Controls, Evidence, Threat Modeling, Risk Scenarios | Project |
| Workflow | Workflow Runs, future Workflow Reporting | Project and global/operator |
| Administration | Current Project Admin, future Identity Administration | Project and global/operator |

`Workflow Runs` remains the ADR-061 telemetry/economics surface.
`Workflow Reporting` is a future cross-project observation surface over that
same read-model. It does not imply live execution, pending gate actions, or
run controls.

`Project Admin` stays project-local tooling. `Identity Administration` is a
global/operator surface for users, groups, roles, permissions, and
project-access grants. Credential ownership is a later GC-P024 surface after
the authoritative credential-store cutover. Do not merge these surfaces
simply because both are "admin."

### Page Placement

| Existing or planned page | Target placement |
|--------------------------|------------------|
| Projects | Global/operator entry point. |
| Dashboard | Project Overview. |
| Requirements | Project Requirements. |
| Requirement Detail | Project Requirements detail route. |
| Traceability Matrix | Project Traceability and Verification. |
| Test Runs | Project Traceability and Verification. |
| Test Runner | Project Traceability and Verification detail route. |
| Graph | Project Graph and Analysis, with full-bleed layout preserved. |
| Analysis | Project Graph and Analysis. |
| Portfolio | Project Assurance. |
| Controls | Project Assurance. |
| Evidence | Project Assurance. |
| Threat Modeling | Project Assurance. |
| Risk Scenarios | Project Assurance. |
| Workflow Runs | Project Workflow telemetry and historical reporting. |
| Workflow Reporting | Global/operator and project cross-project run observation over the same telemetry read-model. |
| Current Admin | Project Administration. |
| Identity Administration | Global/operator Administration. |

### Project and Context Switching

- The active project remains URL-addressable via `/p/:projectId/...`.
- Switching projects should preserve the current sub-route when the destination
  project can support it; otherwise it should route to the destination
  dashboard and show a non-blocking notification.
- Global/operator pages should expose a project filter or project group where
  useful, but they should not rely on a hidden "active project" for
  authorization or data scoping.
- Cross-project workflow reporting must be scoped to projects the current
  user can access, based on GC-P024 project-access grants once implemented.
- Breadcrumbs should show global scope, project scope, and entity detail
  hierarchy without duplicating the primary navigation.

## Shell Layout

### Desktop

- Top bar: product identity, current scope, project switcher, notifications,
  help/documentation entry, and user menu.
- Primary navigation: grouped rail or sidebar with icons and text labels.
- Main content: full-width working area with predictable page headers,
  filters, and detail panels.
- Full-bleed exceptions: graph and other canvas-heavy pages may own the full
  viewport below the shell while retaining global chrome.

### Small Screens

- Collapse grouped navigation into a drawer or menu.
- Keep project switcher, notifications, and user menu reachable from the top
  bar.
- Tables must degrade to horizontal scroll or card rows only when the row
  actions and identifiers remain visible.
- Gate-action and destructive-confirmation flows must remain keyboard usable
  and must not depend on hover-only controls.

### User Menu and Notifications

The user menu should display:

- signed-in principal display name or login name;
- compatibility role projection during the ADR-085 migration;
- available global/admin affordances based on server-provided authorization
  hints;
- sign-out action.

Notifications should support at least:

- session expiry and sign-in required notices;
- operation success/failure;
- workflow observation refresh or final reported outcome;
- background refresh or stale-data warnings.

The notification surface is UX only. Authorization remains enforced by the API.

## Design Tokens

The Tailwind `@theme` block in `frontend/src/main.css` is the starting point.
GC-Q015 should formalize it into named semantic tokens rather than scattering
raw classes across pages.

### Color Roles

Use semantic roles rather than feature-owned colors:

| Role | Use |
|------|-----|
| Background / foreground | App canvas and primary text. |
| Card / card foreground | Bounded content surfaces and repeated items. |
| Border / input | Dividers, table boundaries, form controls. |
| Primary | Main action, selected navigation, primary focus. |
| Accent | Hover/selected low-emphasis surface. |
| Info | Neutral progress, running workflow state, informational notices. |
| Success | Passed, merged, completed, active, enabled. |
| Warning | Pending, ready for review, stale, needs attention. |
| Danger | Failed, destructive action, denied, revoked. |
| Evidence / assurance | Assurance-specific highlights where a neutral info color is insufficient. |

Do not let the app collapse into one blue-only palette. State colors must be
semantically distinguishable and meet WCAG 2.1 AA contrast for text and key
state indicators.

### Type and Spacing

- Page title: one visible H1 per route.
- Section headings: compact and scannable; avoid hero-scale type inside
  workspaces.
- Table and form text: 14px equivalent default, with smaller helper text only
  for metadata.
- Spacing: use a small set of increments already common in Tailwind (`1`, `2`,
  `3`, `4`, `6`, `8`) and avoid page-local custom spacing.
- Radius: default controls and cards should stay at or below 8px unless a
  component has an accessibility or design-system reason to differ.

## Component Inventory

GC-Q015 should promote components from current repeated patterns when there are
real call sites. The target library should include:

| Component | Purpose | Existing starting point |
|-----------|---------|-------------------------|
| AppShell | Authenticated frame, top bar, grouped navigation, responsive layout | `AppLayout` |
| ProjectSwitcher | Project context selection and route preservation | `ProjectSwitcher` |
| UserMenu | Principal display, identity/admin affordances, sign-out | `SignOutButton` plus future user endpoint |
| NotificationCenter | Toasts and durable notices | `components/ui/toast` |
| StatusBadge | Typed state display with semantic colors | `status-badge.tsx`, `components/ui/badge.tsx`, page-local badges |
| DataTable | Sort, filter, pagination, row actions, empty/loading/error states | Requirements and workspace tables |
| FilterBar | Compact scope and search controls | Workflow Runs and workspace filters |
| FormField | Label, hint, validation, disabled/read-only states | `components/ui/form-field.tsx` |
| Modal / ConfirmDialog | Blocking decisions and destructive confirmation | `components/ui/modal.tsx`, `confirm-dialog.tsx` |
| SlidePanel | Detail/edit panels without route loss | `components/ui/slide-panel.tsx` |
| MetricCard | Small numeric summary cards | page-local cards in dashboard/workflow/GRC pages |
| EmptyState | Meaningful absence with optional action | page-local empty paragraphs |
| ErrorPanel | API and authorization error display | `WorkspaceError` and page-local panels |
| LoadingState | Skeletons and spinners sized to their container | `PageSkeleton`, `WorkspaceLoading` |
| DurableRecordViewer | Plans, review findings, decision records, final reports - rendered read-only from the issue thread | new for GC-Q016 |
| RunTimeline | Recorded phase history, outcome, and timing from the telemetry read-model | new for GC-Q016 |

Component APIs should accept typed domain state and render display labels at the
edge. CSS class maps are display details, not the source of truth for workflow,
identity, or GRC state.

## Interaction Patterns

### Tables

- Every table has a visible title, result count, loading state, empty state,
  error state, and filter summary.
- Sortable columns expose keyboard-focusable controls and `aria-sort`.
- Row actions live in a predictable trailing column or contextual menu.
- Destructive row actions require confirmation.
- Bulk actions are disabled until a selection exists and must show the
  resulting scope before execution.

### Forms

- Forms use `FormField`-style labels, helper text, validation, and disabled
  states.
- Server validation errors should map to the relevant field when possible and
  otherwise render in a form-level error panel.
- Submit buttons show pending state and prevent duplicate submission.
- Mutating browser requests must use the existing CSRF-aware fetch wrappers.
- Actor, authorization, and project-access decisions are never accepted from
  client-supplied form fields.

### Panels and Detail Views

- Use route-backed detail pages when the detail is shareable or reload-safe.
- Use slide panels for quick edit/detail tasks that should preserve list
  context.
- Panels must trap focus while open and return focus to the triggering control.
- Detail views should use stable tabs only when each tab has a meaningful route
  or repeated cross-entity pattern.

### Empty, Error, and Loading States

- Empty states explain what is absent and, when authorized, expose the next
  action.
- 401 means the browser session is missing or expired; the existing API client
  redirects to `/login`.
- 403 means the user is authenticated but unauthorized; render it in place with
  no route-hiding claims.
- Loading indicators must have stable dimensions so content does not shift.
- Background refresh should not wipe existing content unless the user changes
  scope.

## Authenticated-Session UX

ADR-037 remains the security contract:

- `/login` is served by the standalone login bundle.
- The authenticated app shell is not anonymously accessible.
- Browser mutations echo `XSRF-TOKEN` as `X-XSRF-TOKEN`.
- `/logout` invalidates the server session and returns the user to `/login`.
- API-shaped 401 responses keep JSON error envelopes and are handled by the
  SPA client.

GC-Q015 should add in-app session awareness after the backend exposes the
current-principal/session read needed for it. The shell should then render:

- signed-in user display;
- compatibility role or future ADR-085 role/project-access summary;
- sign-out;
- session-expired notice when navigation to login was triggered by an XHR 401;
- permission-denied panel for 403 responses.

Do not store bearer tokens, passwords, CSRF values, session ids, or generated
API tokens in local storage, session storage, URLs, logs, issue-thread records,
or examples.

## Workflow Reporting UX

Workflow operations are **observation and reporting**. They are built on the
ADR-061 telemetry read-model and the ADR-029 issue thread, which are the only
workflow surfaces that exist. There is no execution-control surface to build
on: issue #1359 removed the orchestrator, its worker, and its signal API.

This boundary is easy to erode by accident. The read-model records that a run
reached a phase; it does not run the phase. `Running` is an **observed
ingestion state** - the last thing an agent reported - never proof of a live
executor the console can steer. A console that renders that state as though it
were a live handle on a process is a control plane with the controls painted
on, which is worse than no console at all. Rendering must therefore stay
honest about latency and staleness rather than implying liveness.

If a workflow control surface is ever reintroduced, it arrives with its own
ADR and its own product decision, and this section is rewritten then - not
anticipated now.

### Run List

The run list should support:

- global/operator view across accessible projects;
- project-scoped view from `/p/:projectId/...`;
- filters for project, repository, issue, requirement, workflow type, runtime,
  final state, outcome, date range, and actor where the backend exposes it;
- status grouping for running, waiting, ready for review, escalated, failed,
  merged, closed, and superseded runs, as last reported by the agent;
- links to the GitHub issue, PR, and durable records where available.

### Run Detail

Run detail should show:

- run identity and scope: project, repo, issue, branch, PR, workflow type;
- recorded phase history and last reported phase, with the time it was reported
  so a stale run reads as stale rather than as active;
- failures and bounded error summaries as reported;
- durable records: plan, review findings, decision records, readiness report,
  final report - rendered read-only, with the issue thread linked as the record
  of authority;
- telemetry and cost summaries where GC-P025/ADR-061 expose them.

Do not display raw prompts, completions, tokens, provider keys, session ids, or
unbounded review bodies. If a durable record is too large, link to the record
of authority and render a bounded summary.

### Human Touchpoints

PR merge remains the single mandatory human touchpoint, it happens on GitHub,
and the console **observes** it - it does not host it. The console has no gate
actions, no operator signals, and no run start, cancel, or retry, because no
contract or API backs any of them.

Where a run is waiting on a human, the console's job is to make that legible
and to route the operator to the surface that owns the decision: the GitHub
issue thread or the PR. Linking out to the record of authority is the feature,
not a placeholder for a button that should exist later. An in-console action
that merely re-posts to GitHub would be a second, weaker record of the same
decision.

## Identity Administration UX

Identity Administration is global/operator scope. The #1412 console slice
should include:

- users and lifecycle state;
- groups and memberships;
- closed-catalog permissions, roles, and role grants;
- project-access grants;
- audit history for access-affecting changes.

The current `ROLE_USER` / `ROLE_ADMIN` projection can appear during migration,
but new UX should be shaped around ADR-085's domain concepts. Password and API
token credential administration waits for the authoritative credential-store
cutover and must use a non-transcript secret path. Tenant organizations,
invitations, and subscriptions stay future work unless their own ADR lands.

## Migration Guidance

GC-Q015 should proceed in this order:

1. Add the shell with grouped navigation and responsive layout.
2. Add current-principal/session read integration when the backend exposes it.
3. Formalize design tokens and component primitives.
4. Migrate existing workspaces group by group with no loss of function.
5. Adopt the generated API client from GC-O014 and remove hand-maintained API
   type mirrors.
6. Add component tests for reusable shell/design-system components.

GC-Q016 is scoped to observation and record-reading over the ADR-061 telemetry
model, so it can proceed on the surfaces that exist today; it does not wait on
a control contract. It must not grow one by implication - a run-control feature
needs a product decision and an ADR first.

## Accessibility and Quality Gates

- Keyboard navigation for shell, nav groups, project switcher, menus, modals,
  and tables.
- Visible focus states with AA contrast.
- Semantic headings and landmarks per route.
- `aria-sort` for sortable columns and accessible names for icon-only actions.
- No hover-only critical actions.
- Component tests for reusable components.
- Playwright coverage for the GC-Q016 observe -> read durable record -> follow
  through to the issue or PR path.

## Non-Goals

- No new authentication model, SSO, MFA, password reset, or signup flow.
- No tenant organization/workspace model.
- No workflow control surface: no run start, cancel, retry, gate action, or
  operator signal. None of these exist to call (#1359), and reintroducing one
  is a product decision with its own ADR, not a console feature.
- No new workflow DSL, dynamic activity/plugin model, or second workflow state
  machine. The telemetry read-model must not become an executor (ADR-061).
- No route hiding as authorization.
- No request-body actor fields or frontend-supplied audit actors.
- No backend schema, controller, route, migration, generated client, or
  component implementation in issue #1274.
