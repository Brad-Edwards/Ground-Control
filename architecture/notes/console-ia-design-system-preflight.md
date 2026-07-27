# Console IA and Design-System Foundations Preflight

Issue #1274 is a design-reference issue for GC-Q015 and GC-Q016. It must
produce the console information architecture and design-system foundations, but
the construction reference has to bind to the repository's existing contracts
instead of inventing a second console model.

**Re-scoped 2026-07-13 (issue #1384).** Issue #1359 removed the Temporal
orchestration lane, so the gate-action / operator-signal / live-run-control
guidance below is void: there is no control surface to parameterize against,
and ADR-028, ADR-081, and ADR-088 are superseded. This note is corrected rather
than left standing, because it is read as construction guidance alongside
`architecture/design/console-ia-design-system.md` - leaving one of the two
stale would just relocate the contradiction. The surviving console is read and
reporting: the ADR-029 issue thread as the durable record, the ADR-061
telemetry model as the projection over it.

## Boundary Decisions

- The deliverable should be a design/reference document, not executable console
  code. Keep actual route, component, endpoint, and workflow-signal
  implementation in GC-Q015/GC-Q016 and their backend prerequisite issues.
- The existing project-scoped route model is the base shape:
  `/projects` plus `/p/:projectId/...`. Project switching stays URL-addressable
  and routes through `ProjectProvider` / `ProjectSwitcher`; it is not tenancy.
- Separate global/operator surfaces from project-scoped workspaces. The current
  `Admin` page is project-admin tooling. Identity administration is global
  product administration and must not be mixed with project import/sync/graph
  tools just because both sound like "admin."
- The current workflow-runs page is ADR-061 telemetry, and that is the whole of
  the workflow surface. There are no gate actions and no live-run control to
  design against. Do not drive operator actions from the telemetry projection,
  and do not treat the projection as a control plane in waiting.
- The authenticated session UX is ADR-037 browser-session UX: standalone login
  bundle, `GC_SESSION` cookie, `XSRF-TOKEN` / `X-XSRF-TOKEN`, `/logout`, and
  `/api/v1/**` JSON 401 handling. Do not introduce bearer-token storage in the
  SPA.

## Existing Surfaces To Cover

The design document should account for every current workspace before adding
new ones:

- Global: Projects.
- Project home and requirements: Dashboard, Requirements, Requirement Detail.
- Traceability and verification: Traceability Matrix, Test Runs, Test Runner.
- Graph and analysis: Graph, Analysis.
- GRC portfolio/workspaces: Portfolio, Controls, Evidence, Threat Modeling,
  Risk Scenarios.
- Operations/admin: Workflow Runs, current project Admin tools.
- New surfaces: Workflow Reporting and Identity Administration.

## Cross-Cutting Concerns To Reuse

- Frontend data access goes through `apiFetch`, `apiUpload`, `apiDelete`, and
  TanStack Query hooks. Reuse their session, CSRF, 401, and error behavior.
- Shared UI should consolidate around the existing `components/ui` primitives,
  `workspace-shared` patterns, Tailwind `@theme` tokens in `main.css`, Radix
  primitives, and `lucide-react` icons. Page-local metric cards, filter shells,
  state badges, empty/loading/error panels, and tables should be promoted only
  when there are real repeated call sites.
- API contracts remain backend-owned. ADR-082 is the forward path for generated
  OpenAPI TypeScript types; do not expand hand-maintained frontend schemas or
  enum mirrors for new identity/workflow surfaces unless the contract migration
  explicitly requires a temporary bridge.
- Backend writes stay thin-controller to service/aggregate to repository:
  controllers resolve project context, validate request DTOs, and delegate.
  Services own transactions and invariants; repositories own queries.
- Errors must continue through `GroundControlException`,
  `GlobalExceptionHandler`, and `ErrorResponse`. The SPA may adapt the message
  for display, but it must not define a second server-error envelope.
- Actor provenance stays `SecurityContext` -> `ActorFilter` -> `ActorHolder` /
  MDC / Envers. No request body, route param, or frontend state may supply an
  actor for identity administration or workflow reporting.

## Security Layers In Scope

- `BrowserSecurityConfig`: anonymous access is limited to `/login`,
  `/logout`, login assets, and browser-required top-level assets. The main SPA
  shell and `/assets/**` stay authenticated.
- `LoginPageController`: `GET /login` streams the standalone login bundle. Do
  not fold login into the main SPA bundle or forward to `/index.html`.
- `ApiSecurityConfig` and `ApiPathMatrix`: bearer and browser callers share the
  same `/api/v1/**` path matrix. New identity or cross-project operator routes
  need explicit entries when they are privileged.
- CSRF: every session-authenticated browser mutation must echo `XSRF-TOKEN` as
  `X-XSRF-TOKEN`. Reuse the existing fetch wrappers and sign-out path.
- Session expiry: API-shaped 401 responses are JSON envelopes that the SPA
  redirects to `/login`; 403 responses are authorization failures and should
  render in place.
- User administration: current V059/ADR-037 user lifecycle goes through
  `UserAdminService`, `UserCredentialPolicy`, the last-admin guard, session
  revocation, and bounded logs. ADR-085 is the extension point for users,
  groups, roles, closed-catalog permissions, and project-access grants.
- OS/process exposure: no design or tooling should require tokens, passwords,
  CSRF values, session ids, or provider credentials in argv, logs, URLs, local
  storage, or issue-thread records.

## Extensibility Seams

- Navigation metadata should have one source when it starts serving routes,
  sidebar groups, breadcrumbs, permissions, and command/menu entries. Until
  then, avoid a speculative navigation framework.
- Workspace state vocabulary should come from typed API contracts and domain
  enums, with display labels and color tokens layered on top. CSS class maps
  are not the source of truth for workflow, identity, or GRC state.
- Durable-record affordances should be parameterized by record type (plan,
  review findings, decision record, readiness report, final report) with the
  issue thread linked as the record of authority, so a new record type is a
  registry entry rather than a new page. There is no operator-signal catalog to
  parameterize against; do not scaffold one speculatively.
- Identity UX should be ready for ADR-085 data roles and project-access grants,
  while the current implementation still projects `ROLE_USER` / `ROLE_ADMIN`.
  Do not make tenant/workspace isolation claims in this slice.

## Gotchas And Anti-Patterns

- Do not create a second project/tenant/workspace hierarchy. `Project` scoping
  is product scoping today; SaaS tenancy is explicitly future work.
- Do not make local workflow state files or GitHub issue comments the console
  authorization boundary. Product UI reads product REST/MCP contracts only; the
  issue thread is the record of authority for workflow decisions, not an
  authorization surface.
- Do not add route-hiding as authorization. Frontend permission hints are UX;
  `ApiPathMatrix` and service-level identity/project authorization checks are
  enforcement.
- Do not model PR merge as a console signal or reintroduce plan approval. ADR-029
  keeps PR merge as the single synchronous human gate.
- Do not use raw prompts, completions, reviewer bodies, tokens, or provider
  keys in live-run status, telemetry, audit rows, logs, or design examples.
- Do not bury low-frequency global administration in each project workspace.
  Identity administration, cross-project workflow reporting, and project-local
  import/sync tools have different scope and authorization semantics.

## Non-Goals For The Design Issue

- No new authentication model, SSO/MFA/password-reset design, SaaS tenant
  lifecycle, workflow DSL, or dynamic activity/plugin execution model.
- No workflow control surface: no run start, cancel, retry, gate action, or
  operator signal. Reintroducing one is a product decision with its own ADR.
- No backend schema, migration, controller, or route implementation as part of
  this design reference.
- No replacement of existing GRC, requirements, graph, or workflow telemetry
  domain concepts. The design language composes those workspaces; it does not
  rename their bounded contexts.
