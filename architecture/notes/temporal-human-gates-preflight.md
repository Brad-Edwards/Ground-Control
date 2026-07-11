# Temporal Human Gates Preflight

Issue #1279 is the phase-4 human-gates slice for GC-O009. It should complete
the merge-gate observation and authorized operator-signal design for the
Temporal `/implement` workflow. This note is architecture guidance only; it
does not implement workflow code, REST or MCP endpoints, schema changes,
database tables, webhook handlers, policy checks, or console UI.

## Boundary Decisions

- Preserve ADR-029 exactly: PR merge is the only synchronous human gate.
  GitHub's merge action is the authoritative event and must be observed by the
  workflow via a polling or webhook-backed observation path. It must not become
  a Temporal signal, REST "approve merge" action, console approval button, or
  operator signal.
- Keep the operator signal catalog closed: cancel, retry-from, and review-cap
  disposition. No plan-approval, merge-approval, arbitrary "continue," or
  arbitrary Temporal signal method is part of the product contract.
- Keep the existing `api/ -> domain/ <- infrastructure/` boundary. REST and MCP
  callers use the product control surface. Domain services own project scoping,
  signal validation, gate authority, and audit. Temporal SDK and GitHub webhook
  details stay in infrastructure adapters.
- Treat Temporal history and Visibility as the execution source of truth.
  PostgreSQL may hold durable audit records, gate configuration, and bounded
  read-model/correlation facts, but it must not become a parallel workflow state
  machine or the driver for phase advancement.
- Audit records for operator signals are product records, not a replacement for
  Temporal signal history. The audit record should capture who attempted the
  action, project, workflow id, run id when known, signal type, contract
  version, bounded reason/disposition fields, authorization result, and time.
  It must not store prompts, completions, raw review transcripts, tokens, or
  provider payloads.
- Merge observation facts are safe correlation data only: project, workflow id,
  repository binding id/coordinates already resolved by server-side config, PR
  number, observed state, merged-at timestamp if available, and provider event
  id for idempotency if a webhook path is used. Do not store raw webhook bodies
  or GitHub tokens in workflow payloads, logs, Search Attributes, REST/MCP
  responses, or audit rows.

## Cross-Cutting Concerns to Reuse

- **Workflow control surface:** extend `WorkflowExecutionController`,
  `WorkflowExecutionService`, `WorkflowControlPort`, and
  `TemporalWorkflowControlAdapter` rather than adding a second start/status/signal
  path. The service is already the authorization, scope, and validation boundary.
- **Signal contracts:** reuse `OperatorSignalType`, `SendSignalCommand`,
  `RetryPhase`, `Reviewer`, `SignalDisposition`,
  `contracts/schemas/workflow/implement-signals.v1.schema.json`, and
  `WorkflowContractConformanceTest`. New signal fields or versions must update
  the schema and conformance checks before Java/MCP drift can ship.
- **Merge observation contracts:** build on `MergeObservationInput`,
  `MergeObservationResult`, `PrState`,
  `contracts/schemas/workflow/merge-observation.v1.schema.json`, and the
  existing `observeMergeState` activity seam. A webhook receiver can feed a
  durable observation store or wake-up hint, but the workflow still observes the
  GitHub merge fact through a typed activity.
- **Project scoping:** keep using `ProjectService.requireProjectIdentifier` and
  `WorkflowExecutionId.belongsToProject`. Cross-project workflow ids must keep
  resolving to not-found before any Temporal or audit detail is revealed.
- **Authorization:** keep the current `ApiPathMatrix` admin gate for signal
  POSTs until GC-P024 project-scoped gate authority lands. When gate authority
  lands, it should replace the route-level admin-only fallback with a domain
  authority check, not bypass the service.
- **Audit actor:** use `ActorFilter`/`ActorHolder` for authenticated REST
  actions. Envers revision actor/reason is useful supporting metadata, but the
  signal decision itself needs an explicit append-only workflow audit/event
  record because a Temporal signal is not a JPA entity mutation.
- **Errors:** use `DomainValidationException`, `AuthorizationException`,
  `NotFoundException`, `ConflictException`, and `ServiceUnavailableException`
  through `GlobalExceptionHandler` and `ErrorResponse`. Do not leak Temporal
  exception names, GitHub webhook parsing internals, workflow existence under
  another project, or raw request payloads in error bodies.
- **MCP bridge:** keep `gc_workflow_execution` as a closed action/field-set
  adapter through REST. It must not call Temporal gRPC directly, accept arbitrary
  URLs/headers/namespaces/task queues, or synthesize signal method names.
- **Contracts and policy:** use the ADR-082 schema surface,
  `run_workflow_payload_contract_check`, enum contract checks, and a repo-native
  policy gate for the #1279 gate-set invariant. The gate-set check should fail
  if plan approval, merge approval, or any operator signal outside the closed
  catalog appears in backend enum/schema/MCP surfaces.
- **Logging and telemetry:** log with SLF4J and safe correlation fields only:
  project, workflow id, run id, issue number, PR number, signal type, activity
  type, attempt, and outcome. Operational counts belong in Temporal Visibility
  or bounded projections; raw webhook bodies and review text do not.

## Security And Validation Layers

- **HTTP authentication and route policy:** bearer and browser chains both pass
  through `ApiPathMatrix`. Signal sends remain privileged. Webhook endpoints, if
  added, need their own authenticated provider verification and must not fall
  through as ordinary authenticated project writes.
- **Bean Validation and command validation:** REST DTOs carry size and enum
  validation; `WorkflowExecutionService` re-validates signal-specific required
  fields. Add any new bounded fields in both places and keep the service as the
  final validator.
- **Project-scope parser:** workflow id ownership is checked before describe,
  signal, audit detail, or merge observation lookup. Use the existing
  digit-suffix ownership predicate to avoid prefix leaks between neighbouring
  project identifiers.
- **Gate authority:** authenticated actor identity comes from
  `ActorHolder`, not caller-supplied body fields. A missing or insufficient
  actor produces a standard authorization envelope and an explicit denied audit
  event when the request has enough project/workflow context to record one.
- **Webhook authenticity and replay:** a webhook path must verify provider
  signature, event id, repository binding, PR number, and project mapping before
  recording any merge observation. Idempotency belongs on the provider event id
  plus repository/PR tuple. Do not trust a webhook payload to name the Ground
  Control project without server-side repository binding.
- **Temporal endpoint exposure:** product callers never use Temporal Web or
  gRPC as an authorization boundary. Signals and visibility queries continue
  through REST/MCP and the domain service.
- **Secret and OS exposure:** GitHub tokens and webhook secrets stay in
  configuration/adapters, never in process argv, workflow history, Search
  Attributes, logs, REST/MCP responses, or audit rows. Any process or GitHub
  call uses the existing argv-based/server-side side-effect helpers or typed
  infrastructure ports.
- **Error envelopes:** malformed signals, bad enums, closed workflow races,
  unauthorized gate actions, and missing executions return the standard
  `ErrorResponse` shape. Cross-project probes must not produce distinguishable
  errors.

## Maintainability Guardrails

- Do not duplicate the signal catalog in independent Java, schema, MCP, and UI
  lists without a policy/conformance check tying them together. One concept:
  three operator controls plus merge observation.
- Do not add a new workflow-execution service, webhook-specific controller
  state machine, or GitHub client when the existing service, port, activity
  seams, and MCP REST adapter already cover the boundary.
- Keep merge observation as an activity-level concern. A webhook receiver may
  reduce polling latency, but it should not directly advance phase E outside
  the workflow.
- Keep audit records append-only and queryable. Updating a "current gate state"
  row without a historical event log loses the gate-authority trail the issue
  requires.
- Keep retry policy in Temporal activity options and expected domain failures
  non-retryable. Do not create custom sleep/retry loops in REST controllers or
  a database worker.
- Keep controller tests as `@WebMvcTest` slices for coverage; use domain unit
  tests for authorization/audit validation, Temporal test environment for merge
  wait/resume behavior, adapter tests for signal and visibility mapping, and
  MCP tests for closed field forwarding.

## Extensibility Seams

- **Merge observation source:** polling and webhook should both feed the same
  typed merge-observation contract. The seam is "observe PR merge fact for a
  resolved repository binding," not "call GitHub from the workflow."
- **Gate authority:** keep authority evaluation behind the domain service so
  the current `ROLE_ADMIN` fallback can evolve into GC-P024 project-scoped
  gate authority without changing Temporal signal names or MCP tool shape.
- **Signal contract version:** add a version field or versioned schema path
  before changing payload semantics. Backward-compatible fields may be optional
  and bounded; breaking changes need a new contract version.
- **Gate-state read model:** expose current phase, outcome, waiting-for-merge,
  escalated phase/reviewer, last signal/audit ids, and merge observation facts
  as a bounded read model derived from Temporal Visibility/queries plus audit
  records. Do not require console clients to reconstruct state from raw
  Temporal history.
- **Future tenants:** keep project-scoped workflow ids and Search Attributes
  stable inside the current namespace. Tenant-to-namespace routing remains a
  future tenancy ADR and must not be smuggled into #1279.

## Gotchas And Anti-Patterns

- Do not model PR merge as `MERGE_APPROVED`, `CONTINUE_AFTER_MERGE`, or any
  other Temporal signal.
- Do not reintroduce a plan-approval gate through a console action, MCP signal,
  REST enum, schema enum, or policy exception.
- Do not allow arbitrary signal names, method names, Temporal namespaces, task
  queues, repository coordinates, headers, URLs, or tokens through REST or MCP.
- Do not trust webhook project identity, issue number, or PR number without
  resolving the server-side repository binding and project scope.
- Do not put raw webhook bodies, raw issue comments, review transcripts,
  prompts, completions, bearer tokens, GitHub tokens, or provider keys in
  Temporal history, Search Attributes, logs, audit rows, REST responses, MCP
  responses, or telemetry projections.
- Do not use PostgreSQL audit/read-model rows to drive workflow phase
  advancement. They are evidence and product visibility, not the workflow
  engine.
- Do not expose Temporal Web as the workflow operations console or rely on
  Temporal gRPC permissions for product authorization.

## Non-Goals

- No LLM provider boundary or LLM-backed activities in #1279.
- No SaaS tenant model, tenant-to-namespace mapping, dynamic workflow plugins,
  or marketplace-loaded activities.
- No replacement of ADR-029 issue-thread durable records or bridge cutover
  before ADR-081's parity conditions are met.
- No console implementation beyond defining the bounded gate-state fields that
  GC-Q016 can consume.
- No generalized workflow DSL or arbitrary activity replacement beyond the
  existing ADR-027 configuration shape and classpath-available activity seam.
