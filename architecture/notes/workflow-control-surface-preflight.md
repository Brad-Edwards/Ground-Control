# Workflow Control Surface Preflight

Issue #1278 is GC-O009 phase 3: expose product-owned REST and MCP surfaces to
start workflow executions, query execution state, and send contract-defined
operator signals. This note is architecture guidance only. It does not
implement endpoints, tools, activities, workers, UI, merge observation, LLM
providers, or transition-bridge behavior.

## Boundary Decisions

- Treat the new surface as workflow execution control, not ADR-061 telemetry.
  `/api/v1/workflow-runs` remains a reporting/correlation projection and must
  not become the executor, state machine, or signal authority.
- Temporal history/Visibility plus Ground Control correlation/configuration
  data are the execution read source. PostgreSQL may store stable correlation
  records such as project, workflow type, workflow ID, run ID, issue/requirement
  anchors, labels, and start actor, but not a mirrored per-phase state machine.
- REST and MCP are the product boundary. Temporal gRPC and Temporal Web remain
  infrastructure surfaces and must not be exposed as the authorization boundary
  for starts, status reads, or signals.
- Controllers stay thin: validate DTOs, resolve project scope through
  `ProjectService`, and call a domain service. Temporal SDK calls belong behind
  an infrastructure adapter; `domain/` remains Temporal-free.
- Signal senders accept only the closed operator signal catalog already defined
  for `/implement`: cancel, retry-from, and review-cap disposition. PR merge is
  observed from GitHub and is not a signal. No plan-approval signal exists.
- The control vocabulary must keep `WorkflowExecution` / Temporal execution
  concepts distinct from ADR-061 `WorkflowRun` telemetry projection concepts,
  even if response DTOs expose user-facing "run" wording.

## Cross-Cutting Concerns To Reuse

- **Contracts:** publish REST request/response DTOs through the backend
  OpenAPI contract and generated TypeScript path, and keep MCP body allowlists
  under the ADR-034/ADR-082 OpenAPI drift gate. Reuse existing workflow signal
  schemas under `contracts/schemas/workflow/` rather than inventing a second
  signal schema.
- **Authorization:** use `ApiPathMatrix` plus
  `contracts/authz/path-matrix.yaml`. Until GC-P024 gate authority lands, every
  signal endpoint is `ROLE_ADMIN`; ordinary project-scoped starts and reads may
  use authenticated access only if the domain service enforces project scope.
- **Project scoping:** resolve the project through `ProjectService` on every
  start/read/signal path. A workflow ID or run ID is not sufficient authority;
  the service must prove the execution belongs to the resolved project before
  returning status or sending a signal.
- **Validation:** use immutable request records with Bean Validation at REST,
  Zod at MCP, schema conformance for Temporal payloads, and service-level
  validation for workflow type, signal type/version, eligible state, and
  project binding.
- **Errors:** throw existing `GroundControlException` subclasses so
  `GlobalExceptionHandler` and `ErrorResponse` remain the only HTTP error
  shape. Do not return raw Temporal exceptions, stack traces, namespaces, task
  queue internals, or provider/parser details.
- **Audit and logging:** rely on `ActorFilter`/`ActorHolder` for the
  authenticated actor at the REST edge and log only safe correlation fields:
  project, workflow type, workflow ID, run ID, issue number, requirement UID,
  signal name/version, and outcome. Do not log signal free text beyond bounded,
  non-secret reason summaries.
- **Temporal client configuration:** reuse the `@ConfigurationProperties`
  pattern established by `TemporalWorkerProperties`. Any new client/namespace
  configuration must be typed, startup-validated, and environment-schema aware;
  no ad hoc env lookups in controllers or MCP.
- **MCP transport:** route named MCP tools through `mcp/ground-control/lib.js`
  request helpers and closed `pick()` body field sets. `gc_query` can expose
  bounded project-scoped GET status reads only after explicit allowlist and
  denylist review; it must not expose signal POSTs, admin-only reads, headers,
  absolute URLs, or arbitrary Temporal paths.
- **Testing and policy:** add `@WebMvcTest` controller slices, security-enabled
  negative auth/cross-project tests, MCP adapter tests, OpenAPI/MCP contract
  coverage, authz-matrix sync rows for privileged paths, and Temporal client
  adapter unit tests. Temporal replay tests do not substitute for REST/MCP
  authorization evidence.

## Security And Validation Layers

- **Spring Security:** every `/api/v1/**` route passes bearer/session auth,
  IP allowlist, CSRF/session rules for the selected chain, and the shared path
  matrix. Signal routes are admin-only until a gate-authority service replaces
  the temporary role check.
- **Project-scope service check:** every start/read/signal resolves the project
  before touching Temporal. Cross-project workflow IDs must return the standard
  not-found/forbidden envelope without leaking whether the execution exists in
  another project.
- **REST parser and Bean Validation:** reject unknown/malformed payloads,
  unsupported workflow types, unsupported signal names/versions, missing
  idempotency keys where required, invalid enum values, overlong reason text,
  and unbounded lists before any Temporal side effect.
- **Temporal adapter:** construct workflow IDs and Search Attributes from a
  closed safe field set only. Do not place raw branch names, raw issue bodies,
  prompts, completions, review transcripts, provider responses, bearer tokens,
  GitHub tokens, Temporal SQL credentials, or secrets in history, Search
  Attributes, REST/MCP responses, logs, or audit rows.
- **MCP boundary:** Zod is the caller-facing shape check; the backend remains
  the semantic validator. MCP must not accept caller-supplied headers, methods,
  base URLs, tokens, Temporal namespaces, task queues, or arbitrary signal
  method names.
- **Error envelope:** `ErrorResponse` must hide raw Temporal status details and
  retry internals. The response can expose stable product status values and
  retryability classifications only when they are part of the contract.
- **OS/process exposure:** the control surface should use the Java Temporal SDK
  and REST helpers directly; it must not shell out to `temporal`, `gh`, `git`,
  or `curl`, and must not put credentials in argv.

## Maintainability Guardrails

- Reuse `ImplementWorkflow`, `ImplementWorkflowInput`, signal records, and the
  workflow schema contracts already present for phase 2. Do not create a
  parallel DTO hierarchy that says the same thing with different enum values.
- Keep execution correlation separate from `WorkflowTelemetryService`. It may
  project or ingest visibility facts later, but it must not drive starts,
  retries, signal acceptance, or workflow completion.
- Keep signal authorization and audit in one service boundary so the temporary
  `ROLE_ADMIN` rule can be replaced by GC-P024 gate authority without editing
  every controller or MCP adapter.
- Keep Temporal visibility reads behind one adapter that merges Temporal
  Visibility/history/query results with Ground Control correlation data. Do
  not scatter `WorkflowClient` queries across controllers, MCP code, and UI
  helpers.
- Add no generic workflow engine abstraction until there is more than one real
  workflow type using the same boundary. Stable workflow type names plus typed
  start/signal records are enough for this phase.

## Extensibility Seams

- **Workflow type catalog:** a closed catalog maps stable product workflow
  types to classpath-available Temporal workflow interfaces, start payload
  schema versions, task queue policy, and project configuration eligibility.
  The next workflow type should add a catalog row and contracts, not a new
  controller family.
- **Signal catalog:** each signal declares name, version, payload schema,
  required authority, eligible workflow states, idempotency/source-action
  semantics, audit shape, and target workflow type. Future signals extend this
  catalog; arbitrary command strings are out of bounds.
- **Visibility adapter:** status reads return a bounded product read model over
  Temporal Visibility/history/query plus correlation data. If later retention
  requires projection tables, they are rebuildable read models with provenance,
  never execution state.
- **Gate authority:** keep the authority check parameterized behind a service
  method so `ROLE_ADMIN` can be replaced by GC-P024 user/group/role grants and
  per-project gate authority.

## Gotchas And Anti-Patterns

- Do not overload ADR-061 workflow-run telemetry rows as live Temporal state.
- Do not expose Temporal Web/gRPC, namespace, task queue, or signal method names
  as product API inputs.
- Do not accept a signal for a workflow outside the caller's resolved project,
  even when the workflow ID is syntactically valid.
- Do not reintroduce plan approval, model PR merge as a signal, or let
  review-cap dispositions bypass the GC-O007 cap/decision rules.
- Do not create duplicate enums for phases, outcomes, workflow types, signal
  names, or dispositions outside the contract/codegen surfaces.
- Do not serialize JPA entities, request DTOs, exceptions, raw Temporal history,
  or provider-native payloads into workflow history or REST/MCP responses.
- Do not add skill prose as an enforcement layer for this surface. Enforcement
  belongs in REST services, MCP tools, contracts, policy checks, and tests.

## Non-Goals

- No console UI, transition bridge, LLM provider selection, merge webhook,
  tenant model, dynamic plugin execution, or Temporal namespace-per-project
  decision in this phase.
- No replacement of ADR-029 issue-thread durable records before ADR-081 cutover
  criteria are met.
- No new workflow DSL beyond ADR-027 project configuration.
- No broad refactor of ADR-061 telemetry, existing MCP workflow primitives, or
  deterministic phase-2 workflow code unless a concrete contract conflict is
  discovered.
