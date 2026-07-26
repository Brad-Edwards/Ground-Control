# ADR-090: Production-Line Measurement Model

## Status

accepted

## Date

2026-07-26

## Context

Ground Control currently records five useful but non-interoperable signals:
ADR-036 local step JSONL, ADR-061 workflow-run reporting, ADR-059 MCP tool
usage, the planned Micrometer/OpenTelemetry runtime surface (#1285), and the
process evaluation work named by #1297. Their similarly named fields do not
mean the same thing. In particular, a successful MCP call or routed-step
`outcome: ok` is not evidence that a quality gate passed.

The production-line questions are therefore not answerable reliably: where
does a work item first pass, how much retry/rework did a gate cause, and which
defects escaped an intended inspection point? Retrofitting those definitions
after dashboards exist would make historical comparisons misleading.

## Decision

### 1. One logical model; existing stores remain owners

This ADR defines a **logical measurement contract**, not a generic telemetry
table, workflow state machine, dashboard, or new execution abstraction. The
existing stores retain their responsibilities:

- ADR-029 GitHub issue records remain the durable workflow/audit record.
- ADR-061 `WorkflowRun` and `WorkflowPhaseEvent` remain the product reporting
  projection and the home for process-linked, project-scoped facts.
- ADR-059 `McpToolEvent` remains one operational fact per MCP tool invocation.
- ADR-036 JSONL remains local, opt-in economics measurement.
- OpenTelemetry remains runtime observability, not a replacement reporting
  database or an issue-thread parser.

Each emitter maps its native record to this model. A key it cannot know is
absent, never synthesized as `unknown`, copied from raw input, or joined by a
heuristic. `unobserved` is permitted only when a source can attest that the
dimension applied but cannot observe its value; it is not a replacement for an
absent correlation key. The common shape is the contract; it does not require
every source to persist every dimension immediately.

### 2. Canonical dimensions and correlations

| Dimension | Canonical identity and use | Guardrail |
|---|---|---|
| Work item | Exact `(project, repo, issue_number)` GitHub-issue tuple. Requirement UIDs and PRs are related references, not replacements. | No branch-only or issue-number-only cross-repository join. |
| Run | Existing ADR-061 `workflow_run.id`; its current idempotent natural key is `(project, repo, issue_number, branch)`. | A run is a reporting correlation, not workflow authority or a new distributed trace id. |
| Station (gate) | Stable machine `station_id`, mapped from the existing ADR-061 `phase` vocabulary or a registered workflow stage. | A numbered SKILL `step`, UI label, or free-text phase is an alias, never the identity. |
| Station attempt | Ordered attempt for one `(run, station_id)`, using existing `cycle_index` when available; otherwise an explicitly emitted `attempt_index`. | Retry ordering must be deterministic; a source with neither cannot contribute to iteration metrics. |
| Boundary | A registered architecture/contract boundary identifier associated with a station attempt. One attempt may inspect zero, one, or many boundaries. | A process station is not an architecture boundary. Do not use a file path, package name, or display title as a durable boundary id. |
| Capability tier | `low`, `medium`, `high`, `not_applicable`, or `unobserved`. The first three retain ADR-036's provider-neutral meaning. | Provider/model is a separate dimension. `unobserved` is not evidence of `low`; `not_applicable` means no model choice applied. |

Every new process-linked record carries `measurement_version`, `emitter`, and
an observation time, plus every correlation above that the emitter can
authoritatively supply. `project`, `repo`, and `issue_number` are mandatory
for a record claiming work-item or run correlation. High-cardinality
correlations (`run_id`, issue number, branch, boundary) are event/trace fields,
never Micrometer/Prometheus metric labels.

### 3. Keep three outcome axes separate

The model names three non-interchangeable fields:

- **Operation outcome:** whether an emitter operation ran (`ok`, stable error
  code, `skipped`). ADR-036 and ADR-059 already capture this axis.
- **Station result:** `pass`, `fail`, `skipped`, `cancelled`, `not_evaluable`,
  or `unobserved` for the thing a station inspected. Only this axis feeds
  yield/rework formulas.
- **Run outcome/state:** the existing ADR-061 lifecycle/economic result
  (`MERGED`, `CLOSED_WITHOUT_MERGE`, `RUNNING`, and related final states).

`PhaseEventType.COMPLETED`, `McpToolEvent.outcome`, and JSONL `outcome` do not
become `station_result=pass` merely by name. A station-result producer must
state its result explicitly and validate it against the closed vocabulary.

### 4. Process variables and formulas

For a run `r`, station `s`, and its time-ordered **evaluable** attempts
`A(r,s) = (a1, ..., an)`, evaluable means station result `pass` or `fail`.
Skipped, cancelled, and not-evaluable attempts remain measurable coverage but
are excluded from these denominators.

- **First-pass yield (FPY)** for station `s` is
  `count(A(r,s)[1] = pass) / count(r with a non-empty A(r,s))`.
  It measures the first evaluable inspection only; a later green retry never
  improves FPY.
- **Iterations to green** for a `(run, station)` is the smallest `i` where
  `A(r,s)[i] = pass`. If no pass occurs, record `unresolved`, do not substitute
  a maximum, zero, or an arbitrary timeout. Report the resolved distribution
  together with unresolved count.
- **Rework** is a failed evaluable attempt followed by another attempt at the
  same station in the same run. For a green sequence it is
  `iterations_to_green - 1`. Time from a failure to the next attempt is
  *time-to-retry*, not active repair time unless an emitter explicitly measures
  active repair work.
- **Gate cost** is a vector, not a fabricated dollar scalar:
  `sum(duration_ms)`, optional model token counts, and optional calibrated
  monetary `cost_proxy + currency`, grouped by station and tier. Cost per green
  is each component divided by the number of runs that first reach `pass` at
  that station. Parallel elapsed time must not be added and called wall-clock
  cycle time; no price table may infer money from tokens.
- **Escape** is a defect/finding first detected downstream after a station
  intended to catch it had passed, with an explicit finding/defect link and
  missed-station attribution. For station `s`,
  `escape_rate(s) = escaped_from_s / (caught_at_s + escaped_from_s)` over
  distinct attributed finding identities. An unlinked later failure is not an
  escape. If attribution coverage is absent, the metric is **unavailable**,
  not zero. Escape cost is the same gate-cost vector accrued from downstream
  detection through measured rework, apportioned only to explicitly attributed
  findings.

All aggregate responses must include numerator, denominator, unresolved count,
and measurement version. A percentage without coverage is not a process fact.
Tool-operation duration is reportable per tool, but contributes to gate cost
only when an explicit, safe station correlation is present.

### 5. Emitter mapping and migration

| Emitter | Valid mapping today | Required migration guardrail |
|---|---|---|
| ADR-036 `gc_log_step_telemetry` JSONL | Local step cost, provider/model, tier, operation outcome, timestamp, issue, and sanitized branch. | Freeze v2 semantics. A versioned successor adds authoritative project/repo/run correlation and stable `station_id`; retain `step` only as an alias. Its `outcome` must stay operation outcome, so it cannot by itself produce FPY. |
| ADR-061 workflow reporting | Work item/run correlation, stable phase/station candidate, attempt/cycle, phase timing, run state/outcome, and existing economics. | Extend its existing DTO → immutable Command DTO → `WorkflowTelemetryService` → Repository path with a closed station-result vocabulary and mapping catalogue. Do not add a parallel generic measurement aggregate. Existing free-text `outcome` and lifecycle event type are historical operational facts; backfill only safe dimensions and mark station result unobserved where it was never recorded. |
| ADR-059 MCP usage | One tool-operation cost/latency/outcome observation, and declared project when present. | Preserve its closed `{tool, action, outcome, duration_ms, project, ts}` capture shape, exactly one event invariant, and fail-open behavior. Do not scrape arbitrary tool arguments for issue/run/boundary context. A future workflow-scoped context must be explicit, allowlisted, and server-side; absent context remains uncorrelated. |
| Micrometer/OpenTelemetry (#1285) | Runtime operation duration/error and bounded station/tier/result dimensions. | Use standard resource attributes (`service.name`, version, deployment environment) plus an allowlisted `gc.measurement.*` event/span vocabulary. Metrics may label only bounded station, tier, result, and emitter values; work item/run/branch/boundary belong only in safe events or traces. The existing host-local Claude OTLP file is diagnostic input, not dashboard truth or a raw-record import source. |
| Process evaluation / #1297 | Boundary-linked findings, explicit detection station, missed-station attribution, and measured repair work when available. | It consumes this ontology rather than creating CLD-specific yield, rework, or escape definitions. Its withdrawn programme status does not authorize a new product surface; any resumed work must first use the versioned contract. |

The contract migration is additive and versioned. Old records remain readable
under their original semantics; aggregates segment or exclude records that lack
the fields required by a formula. No dashboard may silently combine legacy
operation outcomes with station-result data.

### 6. Retention and aggregation semantics

The durability split is deliberate:

- GitHub's issue thread remains the durable record of workflow decisions and
  evidence; telemetry never replaces it or copies raw comment bodies into a
  measurement store.
- ADR-061 and ADR-059 persistence hold the durable **measurement facts** for
  their bounded period. Process-linked raw facts retain for **400 days**, which
  covers the existing 366-day maximum reporting window plus late-ingestion and
  roll-up safety margin. They are project-scoped and redacted.
- Before raw expiry, retain daily station/tier/result roll-ups, including the
  formula numerator/denominator/coverage counters and measurement version, for
  **three years**. A roll-up is operational history, not a replacement audit
  record.
- ADR-036 JSONL is workspace-local and gitignored; it has no central retention
  promise. The current local OTLP collector's file rotation is its host
  diagnostic policy, not this ADR's durable-retention policy.

Expiry must be an owner-specific, tested telemetry cleanup path. It must not
reuse `AuditRetentionJob`, whose role is Envers revision cleanup, and it must
not delete graph-projected workflow facts until roll-up completeness,
referential safety, and snapshot/rebuild effects are verified. A failed or
incomplete roll-up blocks raw deletion.

### 7. Contract, validation, and security boundaries

The next schema is a versioned contract under `contracts/schemas/`; a breaking
field/vocabulary change receives a new version and `contracts/CHANGES.md`
declaration under ADR-082. Backend write paths use `@Valid` request records,
immutable Command DTOs, `@Validated` services, project-scoped repositories,
and the existing `GroundControlException` → `GlobalExceptionHandler` →
`ErrorResponse` path. No measurement-specific error envelope or exception
hierarchy is permitted.

Retention/roll-up and OpenTelemetry allowlist settings belong in validated
`@ConfigurationProperties` objects, not environment-string parsing, skill
prose, or duplicated constants. SLF4J logs use only bounded identifiers,
outcome codes, and retention counts; they never log a measurement payload.

MCP continues to use Zod input shapes and `buildUrl`,
`addAuthorizationHeader`, `RequestError`, and `parseErrorBody`; tokens,
prompts, request/response bodies, stack traces, raw issue comments, headers,
filesystem paths, and provider keys are prohibited from every measurement
record, log, metric attribute, and error response. Existing JSONL containment
and branch sanitisation remain mandatory. REST changes remain behind the shared
`/api/v1/**` security chain, `IpAllowlistFilter`, `ApiPathMatrix`,
`ActorFilter`/`ActorHolder`, and explicit `ROLE_ADMIN` handling for
cross-project roll-ups.

### 8. Enforcement

This model binds emitters through a structural gate, not through prose. The
`measurement-model-sync` rule in `architecture/policies/adr-policy.json`
requires this ADR in any diff that touches a process-measurement emitter
surface: the `workflowtelemetry` and `mcptelemetry` API and domain packages,
and the `gc_workflow_run`, ingest, and MCP tool-telemetry modules. A record
shape that changes without the model re-creates the divergence this ADR closes,
so the gate fails the diff instead of trusting review to notice.

The rule is pinned by `tools/tests/test_policy.py`, so removing it or dropping
the ADR from its `requireAll` list breaks a test rather than silently
disabling the gate.

### 9. Required consumer commitments

A consumer must reference this ADR and the versioned measurement contract
before defining a dashboard, exporter, metric label, or process KPI. #1285
(GC-P025) is the live consumer and carries that commitment. #1297 is closed and
ADR-087 is withdrawn, so no active consumer exists on that side; the commitment
binds any resumed evaluation work rather than a closed issue.

The following independently deliverable work is tracked and blocks consumers
claiming these metrics:

| Dependency | Issue |
|---|---|
| Versioned contract and station catalogue | #1438 |
| ADR-061 station-result vocabulary and safe legacy handling | #1439 |
| Owner-specific retention and daily roll-ups | #1440 |
| Durable sink for ADR-036 step records | #1354 |

Each consumer's bounded attribute mapping stays with that consumer. These are
dependencies, not a new workflow lane or implementation plan.

## Consequences

### Positive

- Yield, retries, cost, and escapes have comparable definitions without
  flattening five storage/authority boundaries into one table.
- Measurements declare their coverage and provenance, making incomplete legacy
  data visible rather than deceptively precise.
- Runtime metrics avoid high-cardinality label explosions and telemetry avoids
  turning secrets or raw agent inputs into observability data.

### Negative

- Legacy records cannot support every formula; dashboards must show
  unavailable/partial coverage until versioned emitters land.
- Explicit station and escape attribution is more work than inferring meaning
  from strings, but is required for defensible metrics.

### Risks and anti-patterns

- Do not call a tool success, phase completion, or merged PR a gate pass.
- Do not make an architecture boundary, file path, GitHub label, or workflow
  phase a synonym for any other dimension.
- Do not create a `ProcessMeasurement` catch-all aggregate, generic event bus,
  duplicate validation/error taxonomy, or per-emitter dashboard schema.
- Do not send issue, run, branch, finding, or boundary identifiers as metrics
  labels; do not import raw host OTLP tool-detail logs into product reporting.
- Do not infer monetary cost from token counts, infer escape attribution from
  temporal order, or turn missing correlation into a synthetic join key.
- Do not use measurement as a gate, cycle counter, compliance evidence, or
  source of workflow state.

## Non-goals

- Implementing a dashboard, collector, exporter, retention job, migration,
  new REST/MCP endpoint, or process-metrics product in this ADR.
- Changing ADR-029's issue-thread authority, ADR-036's opt-in local telemetry,
  ADR-059's fail-open capture, or ADR-061's reporting-not-execution boundary.
- Reinstating the withdrawn CLD programme, introducing Temporal, or creating a
  new human workflow gate.

## Relationship to existing ADRs

- ADR-027: agent-neutral context and privileged-side-effect boundary remain
  unchanged; GitHub interaction stays in the MCP server.
- ADR-029: issue records are the durable workflow/audit record.
- ADR-036: local step economics and provider-neutral tiers map into this model
  without becoming gate truth.
- ADR-059: tool-handler operational observations remain distinct and fail-open.
- ADR-061: the workflow reporting projection is the canonical product home for
  process-linked measurement facts; this ADR supplies its missing semantics
  and retention policy.
- ADR-082: the shared contract is versioned and compatibility-governed.
- ADR-089: no retired GRC product surface is revived by boundary/finding
  measurement.
