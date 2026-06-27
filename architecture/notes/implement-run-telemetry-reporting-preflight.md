# Implement Run Telemetry Reporting Preflight

Issue #859 adds a first-class reporting surface for `/implement` run
economics, phase/gate health, and future Temporal-backed workflow visibility.
This note is architecture preflight guidance only; it does not implement the
model, endpoints, MCP tools, UI, ingestion, or Temporal worker code.

## Architectural Boundary

- Treat workflow telemetry/reporting as a product read side, not as the
  workflow engine. ADR-028 remains binding: Temporal history/visibility is the
  source of truth once GC-O009 owns execution end to end; any PostgreSQL model
  introduced for reporting is a correlation/projection surface, not a gate
  state machine.
- Keep four surfaces distinct:
  - ADR-029 issue-thread records: durable workflow/audit record for bridge-era
    markers, plans, review records, decisions, and final reports.
  - ADR-036 local JSONL step telemetry: opt-in bridge economics measurement.
  - ADR-059 MCP tool usage telemetry: one event per MCP tool call.
  - Issue #859 workflow-run reporting: one run/phase/gate/economics read
    surface exposed through REST, MCP, and UI.
- During the transition bridge, issue-thread phase markers may seed reporting
  facts, but they must not become a second executor. Store provenance for every
  imported observation (`issue_thread`, `temporal_visibility`,
  `manual_import`, etc.) so stale, partial, and superseded bridge data remains
  distinguishable.
- Preserve ADR-029's current gate model. Do not reintroduce a synchronous plan
  approval signal because older GC-O009 prose mentions it. PR merge remains the
  only synchronous human gate unless a new ADR amends GC-O007/ADR-029.
- Project scoping is mandatory, but project scoping is not SaaS tenant
  isolation. Follow ADR-028: one Temporal namespace with project-partitioned
  workflow ids/search attributes is the current boundary; tenant namespace
  mapping requires a later ADR.

## Cross-Cutting Concerns to Reuse

- **Backend layering:** keep controllers thin under `api/`, use a domain
  service for reporting orchestration/window policy/project resolution, and put
  queries behind repositories. Domain code must not import Spring Web or
  Temporal SDK classes; REST controllers must not call Temporal clients,
  workers, or repositories directly.
- **Project scoping:** route every project-specific read/write through
  `ProjectService.requireProject*` / `resolveProject*` and project-scoped
  repository queries. Cross-project aggregate reads need an explicit
  authorization decision; they must not accidentally fall through as ordinary
  authenticated project reads.
- **Validation and commands:** use API request records with Jakarta Bean
  Validation, immutable domain command records for writes/imports, and service
  validation for semantic rules such as time-window bounds, source/provenance
  enums, and nullable cost fields.
- **Errors:** throw existing `GroundControlException` subclasses (usually
  `DomainValidationException`, `AuthorizationException`, `NotFoundException`,
  or `ConflictException`) and let `GlobalExceptionHandler` return the
  `ErrorResponse` envelope. Do not create telemetry-specific HTTP errors.
- **Security matrix:** new REST endpoints live under `/api/v1/**` and must pass
  the shared bearer/session path matrix. Project-scoped workflow reads may be
  authenticated plus project-scoped; cross-project economics/operator rollups
  should follow the `mcp-tool-usage` aggregate precedent and be admin-only
  unless a more granular project authorization model exists.
- **Persistence:** use Flyway, JPA repositories, and database-side aggregation.
  Append-only telemetry facts generally do not need Envers; mutable product
  configuration or manually edited records should use the repo's normal audited
  aggregate pattern when auditability matters.
- **MCP:** if the surface is exposed through MCP, reuse `buildUrl`,
  `addAuthorizationHeader`, `RequestError`, `parseErrorBody`, `gc_query`
  allowlist/drift tests for reads, and existing issue-comment marker parsers
  for transition ingestion. GitHub writes/reads needed by the bridge stay in
  the MCP server, not in Codex/Claude sandboxes.
- **Configuration:** Temporal client/worker, visibility, retention, and LLM
  provider settings belong in `@ConfigurationProperties` or persisted
  project-scoped config. Do not add a second workflow DSL or parse activity
  lists from `skills/implement/SKILL.md`.
- **Logging/audit:** use SLF4J/MDC correlation fields such as project,
  workflow id, run id, issue, PR, phase, activity, and outcome. Actor identity
  should come from `ActorFilter`/`ActorHolder` for user-triggered REST actions.
- **Frontend:** use `apiFetch`, `useProjectContext`, React Query hooks,
  `frontend/src/types/api.ts`, and the existing project route/layout patterns.
  A workflow dashboard should live inside the project-scoped app surface unless
  it is explicitly an admin cross-project view.
- **Tests:** controller changes need `@WebMvcTest` coverage; aggregation/window
  math needs domain/repository tests; security-path changes need shared matrix
  tests; migrations need smoke coverage; frontend dashboard hooks/components
  need focused Vitest tests.

## Security Layers In Scope

- **Source parsers:** bridge ingestion from GitHub issue comments must trust
  only canonical marker families, handle malformed/reserved-marker-shaped text,
  and record parse failures as bounded outcomes rather than raw comment dumps.
- **Temporal visibility/history:** Search Attributes must contain only safe
  correlation fields. Do not put prompts, completions, raw reviewer payloads,
  bearer tokens, provider API keys, GitHub tokens, or secrets in Temporal
  history, Search Attributes, logs, REST responses, MCP responses, or UI state.
- **Manual/imported cost data:** nullable subscription/token/cost proxy fields
  need size/range/currency/provider validation and provenance. Provider-native
  raw usage payloads should be normalized or discarded, not persisted verbatim.
- **REST auth and CSRF:** browser mutations use the existing session/CSRF
  path; bearer callers use ADR-026 token routing. 401/403 responses must stay
  in the existing JSON envelope and must not reflect secret-bearing inputs.
- **OS/process exposure:** no token or provider secret may be passed in process
  argv, comments, telemetry records, or error messages. MCP/backend code should
  use existing HTTP adapters or argv-array GitHub helpers where applicable.
- **Frontend exposure:** dashboards must render aggregate facts, counts,
  durations, statuses, and safe links only. They must not expose prompts,
  completions, raw reviewer transcripts, raw issue-comment bodies, or secret-
  shaped error detail.

## Extensibility Seams

- Use stable workflow/run dimensions: project, repo, issue, PR, branch,
  requirement UIDs, workflow type, runtime/driver, start/end timestamps,
  final state, merge/close outcome, and superseded/abandoned markers.
- Use stable phase/gate/activity identifiers instead of user-visible prose as
  keys. Step replacement/configuration should select a registered activity by
  stable name, matching ADR-028, not by dynamic code loading.
- Normalize LLM economics to provider, model, invocation counts, wall-clock
  duration, token fields when available, nullable cost proxies, and provenance.
  Keep provider-specific pricing/import policy behind one service/config seam.
- Design aggregates by query dimensions the issue names: project, repo, date
  range, agent/runtime, requirement, workflow type, and outcome. Avoid
  materializing all raw events in JVM or frontend memory to compute dashboards.
- If long-retention reporting outgrows Temporal visibility retention, add an
  explicit read-model projection with rebuild/retention semantics. It must not
  drive workflow execution, retries, signals, or gate completion.

## Gotchas and Anti-Patterns

- Do not create a PostgreSQL workflow state machine that mirrors every Temporal
  event and then drives behavior from the mirror.
- Do not conflate MCP tool usage telemetry with workflow-run telemetry. One
  counts tool calls; the other explains run/phase/gate outcomes and economics.
- Do not use ADR-036 JSONL files as the product source of truth. They are
  bridge-era local measurements and may be missing or partial.
- Do not treat project identifiers as tenant isolation or create a Temporal
  namespace per project.
- Do not store prompts, completions, raw reviewer payloads, GitHub tokens,
  provider keys, stack traces, or raw comments as telemetry fields.
- Do not put aggregate reads behind an unbounded default window or compute them
  by loading all rows/events into memory.
- Do not duplicate workflow configuration, LLM provider selection, marker
  parsing, GitHub clients, error envelopes, auth checks, or frontend API
  clients for this feature.
- Do not add prompt text in skills as the enforcement layer for reporting
  correctness. Validation, redaction, scoping, and query policy belong in
  backend/MCP code and tests.

## Whole-Repo Surfaces In Scope

- ADR-028, ADR-029, ADR-036, ADR-059, and this note for the workflow,
  visibility, and telemetry boundary.
- `backend/src/main/java/com/keplerops/groundcontrol/api/**`,
  `domain/**`, `infrastructure/**`, `shared/security/**`,
  `api/GlobalExceptionHandler.java`, and `shared/web/ErrorResponse.java`.
- `backend/src/main/resources/db/migration/` for any reporting tables,
  indexes, and retention-supporting schema.
- `mcp/ground-control/lib.js`, `gc-query.js`, `README.md`, and tests for MCP
  read exposure, transition ingestion, auth routing, and drift surfaces.
- `frontend/src/lib/api-client.ts`, `frontend/src/contexts/project-context.tsx`,
  `frontend/src/routes.tsx`, `frontend/src/types/api.ts`, and project-scoped
  dashboard/pages/hooks for the web UI.
- `docs/API.md`, OpenAPI/schema tests, security matrix tests, migration smoke
  tests, and `make policy` for repo-native drift detection.

## Non-Goals

- No Temporal worker/activity implementation in this preflight.
- No new human gate and no plan-approval signal.
- No SaaS tenant model or Temporal namespace-per-tenant mapping.
- No dynamic executable plugin/activity loading.
- No replacement for ADR-029 issue-thread records during the transition
  bridge.
- No OpenTelemetry/Prometheus collector decision unless a later requirement
  asks for runtime observability beyond the product reporting surface.
