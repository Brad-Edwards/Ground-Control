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

**Re-scoped 2026-07-29 (GC-Q015 preflight).** ADR-089 retired the composed GRC
console workspaces after GC-Q015 was drafted. Those absent routes are not
migration targets. This note also treats ADR-082's generated TypeScript surface
and compatibility re-export as the contract boundary that exists, rather than
calling for a second generated HTTP runtime.

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
- Current-principal display needs one credential-free, backend-owned response
  contract. It is not the admin user-list DTO, an identity entity, or a
  frontend-decoded session cookie. Server-provided capability hints improve UX
  only; backend authorization remains authoritative.

## Existing Surfaces To Cover

The design document should account for every current workspace before adding
new ones:

- Global: Projects.
- Project home and requirements: Dashboard, Requirements, Requirement Detail.
- Traceability and verification: Traceability Matrix, Test Runs, Test Runner.
- Graph and analysis: Graph, Analysis.
- Operations/admin: Workflow Runs, current project Admin tools.
- New surfaces: Workflow Reporting and Identity Administration.

ADR-089 retains lower-level control, evidence, finding, asset, risk-scenario,
and threat-model aggregates without retaining their composed console
workspaces. GC-Q015 must not restore those pages to satisfy stale issue wording.

## Cross-Cutting Concerns To Reuse

- Frontend data access goes through `apiFetch`, `apiUpload`, `apiDelete`, and
  TanStack Query hooks. Reuse their session, CSRF, 401, and error behavior.
  Extend the canonical `ApiError` adapter if forms need the existing
  `ErrorResponse.error.code` and `detail` fields; do not parse server errors in
  each component. Query retries must not retry 401 or 403 responses.
- Shared UI should consolidate around the existing `components/ui` primitives,
  the requirements table patterns, the workflow `MetricCard`, Tailwind
  `@theme` tokens in `main.css`, Radix primitives, and `lucide-react` icons.
  Page-local filter shells, state badges, empty/loading/error panels, and tables
  are promoted only when there are real repeated call sites.
- API contracts remain backend-owned. ADR-082's committed OpenAPI artifact,
  `tools/contracts/generate-contracts.mjs`, and
  `contracts/gen/typescript/api.ts` own the TypeScript surface.
  `frontend/src/types/api.ts` remains a generator-owned compatibility re-export;
  deleting it or adding DTOs to it fails the contract workflow. Remove the
  remaining local `ProjectResponse` mirror from `use-projects.ts`. Improve weak
  generated field types in the generator, never with page-local interfaces.
- Generated TypeScript is compile-time shape, not runtime validation. Backend
  Bean Validation and domain invariants remain authoritative. Do not hand-copy
  generated DTOs into parallel Zod schemas; any broad runtime-validation
  decision must derive from the same OpenAPI contract.
- Backend writes stay thin-controller to service/aggregate to repository:
  controllers resolve project context, validate request DTOs, and delegate.
  Services own transactions and invariants; repositories own queries.
- Controller contract coverage uses the existing `@WebMvcTest` slice pattern.
  Browser chain, CSRF, logout, and session-expiry behavior stays in the focused
  ADR-037 security integration tests; a controller slice alone cannot prove
  filter-chain behavior.
- Errors must continue through `GroundControlException`,
  `GlobalExceptionHandler`, and `ErrorResponse`. The SPA may adapt the message
  for display, but it must not define a second server-error envelope.
- Actor provenance stays `SecurityContext` -> `ActorFilter` -> `ActorHolder` /
  MDC / Envers. No request body, route param, or frontend state may supply an
  actor for identity administration or workflow reporting. `ActorHolder` is an
  audit projection, not a current-user profile API.

## Security Layers In Scope

- `BrowserSecurityConfig`: anonymous access is limited to `/login`,
  `/logout`, login assets, and browser-required top-level assets. The main SPA
  shell and `/assets/**` stay authenticated.
- `LoginPageController`: `GET /login` streams the standalone login bundle. Do
  not fold login into the main SPA bundle or forward to `/index.html`.
- `ApiSecurityConfig` and `ApiPathMatrix`: bearer and browser callers share the
  same `/api/v1/**` path matrix. New identity or cross-project operator routes
  need explicit entries when they are privileged.
- Current-principal read: source it from the authenticated
  `SecurityContext`/`IdentityPrincipal` seam and return a generated,
  credential-free API response. Do not expose authorities as enforcement,
  authentication details, session ids, CSRF values, credential metadata, or
  admin-only identity records.
- Project admission: `/api/v1/projects` currently lists every project and
  project-owned repositories do not yet enforce ADR-085 admission globally;
  issue #1457 owns that cutover. The shell must not claim that filtering or
  hidden navigation provides isolation. It consumes backend-filtered projects
  when that enforcement lands.
- CSRF: every session-authenticated browser mutation must echo `XSRF-TOKEN` as
  `X-XSRF-TOKEN`. Reuse the existing fetch boundary for sign-out/session
  behavior instead of keeping a third cookie parser in the shell. The login
  bundle remains intentionally independent because ADR-037 makes it a separate
  anonymous asset graph.
- Session expiry: API-shaped 401 responses are JSON envelopes that the SPA
  redirects to `/login`; a fixed non-secret reason may distinguish expiry UX,
  but no return URL or response text is reflected. 403 responses are
  authorization failures and render in place.
- User administration: current V059/ADR-037 user lifecycle goes through
  `UserAdminService`, `UserCredentialPolicy`, the last-admin guard, session
  revocation, and bounded logs. ADR-085 is the extension point for users,
  groups, roles, closed-catalog permissions, and project-access grants.
- OS/process exposure: no design or tooling should require tokens, passwords,
  CSRF values, session ids, or provider credentials in argv, logs, URLs, local
  storage, or issue-thread records.
- Configuration shapes: this feature needs no new environment key or cookie
  tuning. `application.yml`'s hardened `GC_SESSION` settings,
  `SecurityProperties.validate`, production compose passthrough,
  `deploy/docker/env.schema`, and `validate-env.sh` remain unchanged. Any later
  operator-facing setting must update and validate that whole boundary rather
  than bypassing it in frontend build-time configuration.

## Extensibility Seams

- Navigation metadata should have one source when it starts serving routes,
  sidebar groups, breadcrumbs, permissions, and command/menu entries. Until
  then, avoid a speculative navigation framework. Do not extract it below three
  real consumers.
- Project switching preserves a parameterized workspace route key, not the raw
  remainder of the current URL. Entity-detail identifiers are project-owned and
  must not be carried into another project.
- Shell capabilities use server-derived closed permission keys plus optional
  project context, matching ADR-085's effective-authorization seam. Do not
  parameterize navigation on `ROLE_ADMIN` display strings or treat project
  access as tenancy.
- Workspace state vocabulary should come from typed API contracts and domain
  enums, with display labels and color tokens layered on top. CSS class maps
  are not the source of truth for workflow, identity, requirement, or test
  state.
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
- Do not restore the ADR-089-retired GRC pages under an "Assurance" label. The
  retained aggregates are not a retained composed product.
- Do not preserve requirement IDs, run IDs, or other entity-detail path
  segments when switching projects.
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
- Do not delete the generator-owned frontend contract shim, add another
  frontend DTO mirror, or mistake permissive generated `any` fields for runtime
  validation. Fix contract typing at the generator and run `make contracts`.
- Do not introduce a second fetch client, per-component error parser, logout
  cookie parser, or unconditional retry behavior around authentication errors.
- Do not build an all-purpose table, form, or status abstraction before three
  real call sites establish the shared API. A documented interaction pattern
  and small accessible primitives are sufficient.
- Do not encode meaning only in raw Tailwind colors. Semantic tokens need
  verified WCAG 2.1 AA foreground/background and focus pairs, and status needs
  a text or icon cue.
- Do not bury low-frequency global administration in each project workspace.
  Identity administration, cross-project workflow reporting, and project-local
  import/sync tools have different scope and authorization semantics.

## Non-Goals For The Design Issue

- No new authentication model, SSO/MFA/password-reset design, SaaS tenant
  lifecycle, workflow DSL, or dynamic activity/plugin execution model.
- No notification persistence model or backend notification aggregate; the
  GC-Q015 notification surface is shell UX over existing operation/session
  state.
- No restoration of retired GRC console workspaces.
- No workflow control surface: no run start, cancel, retry, gate action, or
  operator signal. Reintroducing one is a product decision with its own ADR.
- No backend schema, migration, controller, or route implementation as part of
  this design reference.
- No replacement of requirements, graph, test, identity, or workflow telemetry
  domain concepts. The design language composes existing surfaces; it does not
  rename their bounded contexts.
