# Temporal Deterministic Core Workflow Preflight

Issue #1277 is the phase-2 deterministic core slice for GC-O009. It should add
the `/implement` Temporal workflow definition, typed deterministic activities,
retry/failure semantics, and replay-oriented tests. This note is architecture
guidance only; it does not implement the workflow, publish the activity
contracts, add REST/MCP control endpoints, add LLM-backed activities, or define
the console.

## Boundary Decisions

- Treat the workflow definition as deterministic orchestration only. Workflow
  code may call activities, wait on Temporal timers, query workflow state, and
  handle contract-versioned signals; it must not call Spring repositories,
  REST clients, GitHub clients, `gh`/`git`, filesystem APIs, Java wall-clock
  APIs, random/UUID generation, or LLM providers directly.
- Keep Temporal SDK types in `infrastructure/temporal`. Domain services,
  repositories, command records, validation, and project resolution stay
  Temporal-free. Activities are the adapter boundary that calls existing
  domain services and infrastructure ports.
- Publish workflow/activity input and output schemas under
  `contracts/schemas/workflow/` before adding Java records that use them.
  Activity records are durable contract payloads, not Spring MVC request DTOs.
  They must carry IDs, enum values, bounded scalars, schema versions, and
  redacted summaries only.
- Do not pass JPA entities, Spring request/response DTOs, exception instances,
  provider-native responses, prompts, completions, raw issue comments, raw
  review transcripts, bearer tokens, GitHub tokens, LLM provider keys, or SQL
  credentials through Temporal history, Search Attributes, logs, REST/MCP
  responses, or Envers rows.
- Preserve ADR-029 gate semantics. PR merge is the only synchronous human gate
  and is observed as an external GitHub event, not modeled as a Temporal signal.
  No synchronous plan-approval gate exists. Operator controls such as cancel,
  retry-from, and review-cap dispositions are explicit signals only when their
  contract, authorization, audit, and project-scope behavior are defined.
- Preserve ADR-028 project scoping. A single Temporal namespace remains the
  topology. Workflow IDs and Search Attributes partition by Ground Control
  project, workflow type, issue/requirement anchors, and safe outcome fields;
  project scoping is not tenant isolation.
- Keep the phase-1 smoke workflow isolated as a smoke/probe. The real
  `/implement` workflow should not grow out of the smoke payload or reuse its
  ad hoc string contract.

## Cross-Cutting Concerns to Reuse

- **Contracts:** ADR-082, `contracts/schemas/workflow/`,
  `contracts/CHANGES.md`, schema invariant metadata, and the policy surface
  for `workflow-payload-contract`. Do not invent a second schema directory or
  rely on Java records as the only contract.
- **Backend layering:** the existing `api/ -> domain/ <- infrastructure/`
  ArchUnit rules. Add narrower rules only for new Temporal risks: no Temporal
  SDK in `domain/`, workflow payloads mapped to schemas, and deterministic
  activities free of LLM provider dependencies.
- **Project scoping:** `ProjectService.requireProject*` and project-scoped
  repositories/services. Direct project identifiers in payloads are correlation
  data; authorization and lookup still go through the service boundary.
- **Domain orchestration:** reuse `RequirementService`,
  `TraceabilityService`, `QualityGateService`, existing GitHub sync/client
  ports, and current status/traceability validation instead of re-implementing
  requirement transitions, link rules, issue resolution, or gate math inside
  activities.
- **Telemetry:** ADR-061 `WorkflowTelemetryService` is a reporting
  correlation/projection surface only. Temporal history/visibility drives
  execution; the workflow-run tables may ingest or project facts but must not
  become a workflow state machine.
- **Bridge workflow records:** ADR-029/ADR-036 MCP renderers and marker
  families (`gc_post_decision_record`, `gc_post_final_report`,
  `gc_render_pr_body`, traceability/GRC reconciliation markers) stay
  authoritative until the cutover conditions in ADR-081 are met. Do not copy
  marker counters into Temporal state or a database row during phase 2.
- **Errors and validation:** Bean Validation on REST DTOs when a REST surface
  arrives, immutable command records for service inputs, `GroundControlException`
  subclasses for expected domain failures, and `GlobalExceptionHandler` plus
  `ErrorResponse` for HTTP responses. Activity wrappers may classify retryable
  versus non-retryable failures, but must not serialize exception objects into
  workflow history.
- **Logging and audit:** SLF4J/Logback with safe correlation fields
  (`project`, `workflowId`, `runId`, `activityType`, `attempt`,
  `requirementUid`, `issueNumber`). REST-accepted operator actions use
  `ActorFilter`/`ActorHolder`; background worker actions use a system/worker
  identity and must not spoof the authenticated user who started the run.
- **Configuration:** `TemporalWorkerProperties` and future Temporal/LLM
  configuration should be `@ConfigurationProperties` with startup validation.
  Project workflow configuration consumes the ADR-027 shape; do not parse
  activity lists from skill prose or add a second workflow DSL.
- **Testing:** Temporal Java test environment for replay determinism,
  signals, retries, and crash/resume; focused unit tests for each activity
  class with fake ports/services; `@WebMvcTest` slices for any controller
  added in later phases; policy tests for schema, gate-set, and boundary drift.

## Security And Validation Layers

- **REST/auth surface:** phase 2 should not expose new product endpoints unless
  the phase scope changes. Later start/status/signal endpoints must pass
  `ApiPathMatrix`, authenticated bearer/session chains, Bean Validation,
  `ProjectService` scoping, `ActorFilter` actor capture, and the standard
  `ErrorResponse` envelope. Operator signals remain admin-only until the
  gate-authority model is active.
- **Temporal endpoint exposure:** Temporal gRPC/Web remain infrastructure
  surfaces. Product callers must not send Temporal signals directly through
  gRPC to bypass REST/MCP authorization and audit.
- **Signal validation:** signal payloads must be versioned contracts with
  closed signal names, bounded enum values, idempotency/source-action fields
  where needed, and project/workflow binding. A denied or malformed signal is
  an audited product decision at the REST/MCP edge, not an unstructured
  workflow exception.
- **Search Attributes and workflow IDs:** use a closed safe field set:
  project identifier, workflow type, issue number, requirement UID, outcome,
  and bounded run identifiers. Do not store raw branch titles, raw issue
  comments, prompts, completions, review text, or secrets in Search
  Attributes. Any branch-derived component must be sanitized before it becomes
  an ID segment.
- **Secret handling:** provider keys, GitHub tokens, Temporal SQL passwords,
  bearer tokens, prompts, completions, and reviewer payloads are not workflow
  payloads. They stay behind infrastructure adapters/configuration and are
  never logged, echoed, stored in history, placed in process argv, or copied
  into telemetry.
- **OS/process exposure:** any activity that shells out or drives Git/GitHub
  must do so behind an infrastructure port using argv arrays and sanitized
  inputs. Workflow methods must never spawn processes. Prefer existing
  server-side/MCP side-effect paths where the bridge already owns a GitHub
  write.
- **Contract validation:** Java records used in workflow history must
  serialize against their JSON Schema, and schema invariants must name their
  enforcing tests/specs. Do not rely on Temporal serialization success as a
  substitute for contract validation.

## Maintainability Guardrails

- Keep each activity cohesive around one existing product operation or
  external side effect. Avoid inventing an activity framework, registry, or
  abstraction until multiple real call sites need it; stable activity names
  and typed records are the abstraction boundary for now.
- Retry policy belongs at the activity-stub/activity boundary, with expected
  domain validation/auth/not-found failures marked non-retryable and transient
  infrastructure failures retryable. Do not implement ad hoc sleep/retry loops
  in workflow code or hide retry counters in a database projection.
- Use existing command/service boundaries for requirement status transitions,
  traceability reconciliation, quality-gate evaluation, GitHub issue/PR
  observation, and workflow telemetry projection. Re-encoding those rules in
  activities creates two sources of truth.
- Keep workflow logic testable by replay. Any helper called by workflow code
  must be pure and deterministic; helpers that need Spring, I/O, clocks, or
  secrets are activities or infrastructure adapters.
- Keep skill-lane and Temporal-lane concepts distinct during the bridge:
  skill prose packaging, MCP marker records, local JSONL telemetry, Temporal
  history, and ADR-061 reporting projections are five different surfaces.

## Extensibility Seams

- **Activity selection:** use stable activity names mapped to classpath
  implementations and project-scoped configuration. This supports "replace
  SonarCloud" or "skip a review tool" without dynamic code loading.
- **Provider selection:** LLM-backed activity support belongs in a later phase
  behind a provider port and project configuration. Deterministic phase-2
  activities must have no LLM provider dependency so phase 5 can add providers
  without rewriting the core graph.
- **Signal catalog:** keep operator signal names, versions, eligible workflow
  states, required authority, idempotency semantics, and audit shape as a
  closed catalog. Future signals extend the catalog instead of accepting
  arbitrary command strings.
- **Visibility projection:** Temporal Visibility is the authoritative execution
  read source. If product reporting needs longer retention, project a bounded
  read model with provenance and rebuild semantics; never use that projection
  to drive workflow execution.
- **Tenancy:** workflow IDs should remain project-scoped inside the current
  namespace so a future tenant-to-namespace resolver can be added without
  changing the core `/implement` identifiers.

## Gotchas And Anti-Patterns

- Do not create a PostgreSQL workflow state machine that mirrors Temporal
  history and then drives behavior from the mirror.
- Do not create one Temporal namespace per Ground Control project.
- Do not put Temporal SDK imports in `domain/` or Spring Web DTOs/JPA entities
  in workflow payloads.
- Do not let the activity I/O Java records drift from
  `contracts/schemas/workflow/`, and do not add duplicate enum vocabularies
  outside ADR-034/ADR-082 contract surfaces.
- Do not reintroduce plan approval, model PR merge as a Temporal signal, or
  weaken the review-cap/no-deferral/traceability gates while moving them into
  activities.
- Do not make branch name, PR number, or commit lineage reset review-cycle
  counters. The issue-thread bridge remains authoritative until cutover.
- Do not let the transition bridge become a second engine with independent
  counters, phase state, retry logic, or gate rules.
- Do not store raw issue-thread bodies, CI logs, Sonar payloads, review
  transcripts, prompts, completions, or command output in Temporal history just
  because an activity can retrieve them.
- Do not expose Temporal Web as the Ground Control workflow UI or use Temporal
  gRPC as the product authorization boundary.

## Non-Goals

- No LLM provider API or LLM-backed activities in phase 2.
- No REST/MCP workflow control surface, operator-signal endpoint, webhook
  receiver, or console UI in phase 2 unless the owning later phase is pulled
  forward with its contracts and auth rules.
- No dynamic executable plugins, marketplace-loaded activities, or workflow
  DSL beyond ADR-027 project configuration.
- No SaaS tenant model or tenant-to-Temporal-namespace mapping.
- No replacement of ADR-029 issue-thread durable records before ADR-081's
  parity and cutover conditions are satisfied.
