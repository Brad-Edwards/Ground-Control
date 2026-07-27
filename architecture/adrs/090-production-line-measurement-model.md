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
| Live `/implement` lifecycle emission | #1435 (delivered) |
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

## Amendment (issue #1435, 2026-07-26): live ADR-061 emitter mapping

Live lifecycle emission uses the existing ADR-061 owners and maps to this
model without a second run, station, or outcome schema:

- work item: exact `(project, repo, issue_number)`;
- run: the `workflow_run.id` resolved by the existing
  `(project, repo, issue_number, branch)` upsert key;
- station: the stable ADR-061 `phase` id, never the SKILL step number,
  user-facing phase label, MCP tool name, or `next_action`;
- station attempt: the authoritative `cycle_index` when one exists, otherwise
  absent rather than inferred;
- observation time: `started_at`, `ended_at`, or event `occurred_at` supplied
  by the tool-layer transition owner;
- run state/outcome: ADR-061 lifecycle fields, kept separate from
  `PhaseEventType`, MCP operation outcome, and ADR-036 JSONL outcome.

The first live producer does not authorize a catch-all lifecycle event or a
second copy of the station catalogue planned by #1438. Its seam is a bounded
workflow-run observation helper in the MCP layer that accepts the explicit
correlation tuple and closed lifecycle/event values, uses the existing REST
client and authentication path, and returns no control-flow authority.
Emitter/version fields introduced by the #1438 versioned contract remain that
contract's responsibility; issue #1435 must not synthesize them, fork the
schema, or reinterpret legacy fields to imitate them.

At-least-once tool execution plus append-only phase events creates a
reconciliation requirement: every logical event needs deterministic source
identity so retries and issue-thread backfill converge. A timestamp is an
observation, not an idempotency key; provenance is a source class, not event
identity; and a cycle index distinguishes attempts but does not identify the
source record. Aggregates must not count both a live observation and its
backfilled copy as two station attempts.

Lifecycle capture inherits ADR-059's failure-isolation pattern, not its record
shape: determine the workflow result first, launch the bounded telemetry write
afterward, catch every write failure, log only safe correlation identifiers
plus a stable failure class, and preserve the original result unchanged.
Unlike ADR-059 tool-usage capture, lifecycle emission must be attached only to
registered workflow boundaries; wrapping every MCP call would conflate tool
operations with production stations.

### Decisions this amendment settles

The open questions above resolve as follows.

**Provenance.** `LIVE_EMISSION` is added to the closed provenance vocabulary.
A fact the tool layer observed as a phase transitioned is not a fact
reconstructed from the issue thread: the two carry different freshness and
different reconciliation semantics, and labelling the first `ISSUE_THREAD`
would erase the seam provenance exists to mark. Backfill keeps `ISSUE_THREAD`,
`MANUAL_IMPORT` stays economics-only, and `TEMPORAL_VISIBILITY` stays
historical.

**Event identity.** `workflow_phase_event.source_id` carries the identity of
the logical fact, unique within the run. When an emitter cannot attest it, the
service derives `phase:eventType:cycleIndex`. Re-recording an existing
`(run_id, source_id)` returns the stored event rather than appending a second
one, so the table stays append-only per logical fact instead of per HTTP call,
and a live observation plus its backfilled copy count as one station attempt.

**Attempt ordinal.** A `STARTED` event opens an attempt and takes the next
ordinal for `(run, phase, STARTED)`, read from durable history so an emitter
restart cannot silently reset it; the emitter threads that ordinal onto the
terminal event so both halves describe one attempt. Every other event type
without an explicit ordinal takes attempt `0`. An emitter that cannot order
attempts is describing the first one, and that is precisely what makes an
unordered reconciliation record converge instead of appending a phantom retry.

**Station vocabulary.** The `/implement` mechanical bands map to stable phase
ids one per gate: `issue_branch_resolution`, `completion_gate`, `git_publish`,
`ci`, `sonarcloud`, `ready_for_review`, `post_merge`. CI and SonarCloud stay
separate stations because they are separate gates with separate rework
profiles; collapsing them would make per-gate first-pass yield meaningless.
The versioned station catalogue remains #1438's.

**Terminal coverage.** The tool layer records `MERGED` when the post-merge
phase completes, `CLOSED`/`CLOSED_WITHOUT_MERGE` when it observes the linked PR
closed unmerged, and `SUPERSEDED` when a new live attempt opens on a different
branch of the same work item, the only abandonment observable at the moment
it happens, since an agent that walks away emits nothing. `FAILED` joins the
run-state vocabulary for an explicitly observed non-recoverable failure; it is
never inferred from a retryable phase failure, and a failed station attempt
leaves the run open. Consistent with the ADR-061 amendment, `RUNNING` means
that no terminal observation has been recorded, and closing the remaining gap
requires the lease/heartbeat decision that amendment defers.

**Transport isolation.** The emitter timestamps each transition locally and appends the write to an
internal FIFO chain that the workflow never awaits. A bounded timeout alone would still put the
backend on the critical path, because it caps the delay rather than removing it. The queue is what
makes observation genuinely unable to stall the observed operation, rather than merely bounded in
how long it stalls. Ordering within a run is preserved by the chain, and the recorded `occurred_at`
is the moment of transition, not the moment of delivery, so a late flush never distorts a duration.

**Enforcement.** `mcp/ground-control/workflow-run-lifecycle.js` and
`mcp/ground-control/gc-implement-mechanical.js` join the
`measurement-model-sync` trigger. A live emitter the gate does not name is a
hole in the gate.

## Amendment (issue #1436, 2026-07-27): live delivery is transport, not a new emitter

Issue #1436 adds a project-scoped SSE transport over the ADR-061 reporting
model. It introduces **no new emitter and no new measurement record**, so the
canonical dimensions, the three outcome axes, and the formulas in Decisions 2–4
are untouched. The stream re-reads the committed `WorkflowRun` and
`WorkflowPhaseEvent` projections and serialises the existing REST response
shapes; a subscriber sees exactly the rows a poll would have returned, only
sooner.

This is stated explicitly because a delivery path is the easiest place to
accidentally grow a second measurement model:

- **No stream-only schema.** The SSE payloads are `WorkflowRunResponse` and
  `PhaseEventResponse`. A stream-only envelope, event vocabulary, or DTO would
  be the parallel measurement shape Decision 1 forbids, and the frontend would
  then be reconciling two shapes of the same fact.
- **Push is not a station result.** Delivering an event is not evidence that a
  gate passed, that a station attempt occurred, or that a run is alive.
  Decision 3's separation stands: the operation outcome of a delivery is not a
  station result and never feeds a yield or rework denominator.
- **No emitter/version fields.** `measurement_version` and `emitter` remain the
  #1438 versioned contract's responsibility. The transport must not synthesize
  them, and re-delivering a fact does not create a new observation of it.
- **Duplicate delivery is not a second attempt.** Delivery is best-effort and
  may repeat; subscribers reconcile by entity id. The `(run_id, source_id)`
  identity settled by the #1435 amendment is what keeps a live frame and its
  backfilled copy counting as one station attempt, and the transport reuses it
  rather than introducing a delivery-side dedup key.
- **`RUNNING` still means "no terminal observation recorded."** An open
  connection is not a lease and a heartbeat is not a workflow liveness signal—
  the heartbeat proves the socket is open, nothing more. Strict liveness and
  stale-run reaping still require the separate lease decision both this ADR and
  ADR-061 defer, and the console must not present transport health as process
  health.

Consistent with Decision 7, the endpoint stays behind the shared `/api/v1/**`
security chain, `IpAllowlistFilter`, and `ApiPathMatrix` with project scoping
resolved through `ProjectService`; its bounds live in a validated
`@ConfigurationProperties` object rather than parsed environment strings; and
its logs carry only bounded identifiers, counts, and a closed disconnect reason.

Because process-local fan-out cannot reach a connection held by another node,
the internal post-commit change notification is the seam a multi-instance
deployment replaces with a broker, outbox, or database notification. That
replacement is a delivery concern and must not become a reason to fork the
measurement model.

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
