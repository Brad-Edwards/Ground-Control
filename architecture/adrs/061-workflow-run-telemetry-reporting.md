# ADR-061: Workflow-Run Telemetry & Economics Reporting Surface

## Status

accepted

## Date

2026-06-24

## Context

High-concurrency `/implement` work leaves durable issue-thread records (ADR-029),
opt-in per-step JSONL economics (ADR-036), and per-MCP-tool-call usage counters
(ADR-059). None of these answer the operator question issue #859 raises: across
roughly eight concurrent agents, which runs are burning subscription/token budget,
where are the gate/CI/review hot spots, and what does a merged PR or closed issue
actually cost? There is no aggregate product surface that connects cost drivers to
workflow outcomes (merged PRs, closed issues, review/CI cycles, stuck gates,
escalations, wall-clock by phase) across repos and agents.

GC-O009 ("Workflow Orchestration via Temporal") will eventually own `/implement`
execution end to end, at which point Temporal Visibility is the authoritative
source of run/phase history (ADR-028). Issue #859 needs the **reporting** half of
that vision now, during the transition bridge, without building the workflow engine.

## Decision

### 1. A reporting read-model, not a workflow engine

Issue #859 adds a PostgreSQL **correlation/projection surface** for workflow runs,
phase/gate events, and economics, exposed through REST, MCP, and the web UI. It is
explicitly NOT a gate state machine and never drives execution, retries, signals, or
gate completion. ADR-028 remains binding: once GC-O009 owns execution, Temporal
Visibility is the source of truth and this model becomes a projection of it. The
read-model must not become a second executor.

### 2. Provenance is the transition-bridge seam

Every persisted run and phase event records a `provenance`
(`ISSUE_THREAD` | `TEMPORAL_VISIBILITY` | `MANUAL_IMPORT`) so stale, partial, and
superseded bridge data stays distinguishable from authoritative data. During the
bridge, facts are seeded from GitHub issue-thread `gc:` markers
(`provenance=ISSUE_THREAD`); the same schema later ingests Temporal Visibility
without a migration. ADR-036 JSONL files are bridge-era local measurements and are
NOT the product source of truth; they may only supplement phase timing.

### 3. Two entities, idempotent upsert

- `workflow_run`: one row per run, keyed for idempotent upsert by
  `(project, repo, issue_number, branch)`. Re-observing a run merges the non-null
  fields of the new observation, so a later phase marker, the merge outcome, or a
  manual cost import refine the same row instead of duplicating it. Carries run
  dimensions, `final_state`, merge/close `outcome`, and economics.
- `workflow_phase_event`: append-only per-phase/gate events (preflight, plan, tdd,
  completion_gate, codex_review, precommit, ci, sonarcloud, test_quality_review,
  transition, traceability_reconcile, issue_close, escalation, …) with a stable
  machine `phase` id (never user-visible prose), `event_type`, `cycle_index`,
  duration, and a denormalized `project` so phase aggregates scope without a join.

Neither table carries an Envers audit shadow: like `mcp_tool_event` (ADR-059) these
are operational telemetry, and an audit history of idempotently overwritten rows is
not worth the storage. `workflow_run` is mutable only through idempotent
re-ingestion, not user-edited configuration.

### 4. Closed, redacted field set enforced in the backend

Only safe correlation and economics scalars are stored. Prompts, completions,
bearer tokens, provider/GitHub keys, raw reviewer payloads, and raw issue-comment
bodies are excluded at the DTO layer, and every caller-supplied string is rejected
if it carries the reserved `<!-- gc:` marker sequence: forged-marker text can never
round-trip into telemetry (cf. GC-TM-004). Bridge ingestion trusts only canonical
`gc:` marker families and records malformed/forged-shaped input as a bounded skip
count, never raw text.

### 5. Project scoping mandatory; cross-project rollup is admin-only

Project-scoped reads/writes route through `ProjectService` and project-scoped
queries (cf. GC-TM-005/GC-TM-007 disclosure classes). The cross-project operator
rollup is a dedicated endpoint, `GET /api/v1/workflow-runs/cross-project-aggregate`,
gated to `ROLE_ADMIN` in the shared `ApiPathMatrix`, an explicit authorization
decision, never an accidental fall-through from a project read (ADR-026). Project
scoping is not SaaS tenant isolation and creates no Temporal namespace per project.

### 6. Aggregation runs in the database, window policy in the service

Throughput counts, outcome counts, cycle-time percentiles
(`percentile_disc` over `ended_at − started_at` minutes), cost sums, and per-phase
hot spots all run as `GROUP BY` / `COUNT FILTER` queries in Postgres against the
`(project, started_at)` and `(project, phase, occurred_at)` indexes; the service
never materializes the raw event window in JVM memory. The default window (30 days)
and maximum window (366 days) are named constants in `WorkflowTelemetryService`. The
time anchor is `COALESCE(started_at, created_at)` so runs without a recorded start
still fall in a window.

### 7. Three surfaces over one model

- **REST** (`/api/v1/workflow-runs**`): record/upsert run, append phase event,
  import cost, list runs, project-scoped aggregate, admin cross-project aggregate.
- **MCP**: `gc_workflow_run` (action-discriminated: record / record_event /
  import_cost / list / aggregate / cross_project_aggregate) and
  `gc_workflow_run_ingest` (bridge ingestion from the issue thread). The two
  project-scoped read paths are added to the `gc_query` allowlist (ADR-035); POST
  and admin paths stay off it. GitHub reads for the bridge stay in the MCP server,
  not in Codex/Claude sandboxes (ADR-027/031).
- **Web UI**: a project-scoped dashboard (throughput, cycle-time distribution,
  review/gate hot spots, cost proxies per merged PR / closed issue, active workflow
  status) rendered with lightweight inline SVG/CSS (no new chart dependency) and
  showing only aggregate facts, never raw bodies.

## Consequences

**Positive:**
- Operators gain per-project and cross-project economics, gate health, and active
  run visibility over any window up to a year, from one read-model.
- The provenance seam distinguishes bridge-sourced data from other origins without
  a schema change (see the 2026-07-12 amendment: the originally anticipated
  Temporal Visibility ingestion path did not materialize).
- The closed, redacted field set plus reserved-marker rejection make leaking
  prompts/secrets into telemetry a deliberate, reviewable code change.

**Negative / risks:**
- Append-only `workflow_phase_event` rows grow without bound; a retention/partition
  strategy is deferred to a follow-on issue (as for `mcp_tool_event`).
- Bridge ingestion fidelity is bounded by what the issue-thread markers record;
  partial/stale runs are expected and flagged by provenance, not hidden.

**Out of scope:**
- No Temporal worker/activity implementation, no new human gate or plan-approval
  signal, no SaaS tenant model, no dynamic plugin/activity loading, and no
  OpenTelemetry/Prometheus decision (those await a later requirement).

## Amendment (issue #1359, 2026-07-12): Temporal transition retracted

GC-O009's Temporal engine (ADR-028, ADR-081) is withdrawn; it will not
eventually own `/implement` execution, and Temporal Visibility will not
become this surface's authoritative source. Every "transition bridge" /
"eventually" framing in the Context and Decision sections above no longer
applies: `ISSUE_THREAD` bridge ingestion (via `gc_workflow_run_ingest`) is
this surface's permanent, sole ingestion path, not a stopgap. This surface's
own decisions - the reporting read-model that never drives execution, the
closed redacted field set, project scoping, the three-surface (REST/MCP/UI)
shape - are unaffected and continue exactly as decided. `TEMPORAL_VISIBILITY`
remains a defined `provenance` value for historical data recorded before this
amendment; no active ingestion path writes it going forward.

## Amendment (issue #1311, 2026-07-19): audited context-graph projection

ADR-084 subsequently made Envers revisions the context graph's canonical time
spine. Its snapshot metadata is only honest when every entity read by a graph
contributor participates in Envers. Decision 3's original no-audit choice is
therefore superseded for `WorkflowRun` and `WorkflowPhaseEvent`: both entities
are audited, and V203 adds their forward-only audit shadows. The unprojected
`workflow_run_requirement_uid` collection remains `@NotAudited`.

The reporting model joins the registered `workflow-and-process` ontology family
through a read-only contributor:

- `WORKFLOW_RUN` represents the persisted reporting run and exposes its stable
  `graphNodeId` on the existing REST response.
- `WORK_ITEM_REFERENCE` identifies a GitHub issue from the exact persisted
  `(project, repo, issueNumber)` tuple. Its graph id is a bounded,
  length-framed digest that includes the project UUID. It is not a new JPA
  aggregate and does not reuse the requirements-specific `ARTIFACT_REFERENCE`.
- `RUN_FOR_WORK_ITEM` records the stable association. Each persisted
  `WorkflowPhaseEvent` becomes a separately identified
  `WORKFLOW_PHASE_EVENT` edge from the run to the work-item reference, preserving
  repeated events between the same endpoints.
- A run with incomplete repository/issue identity remains an isolated run node;
  the contributor never fabricates a work item or self-loop.

Only the closed correlation, lifecycle, provenance, and phase-event scalars
needed for traversal are projected. Branches, provider/model economics, local
JSONL telemetry, prompts, issue bodies, reviewer payloads, and credentials stay
out of the graph. Project UUIDs resolve to immutable project identifiers inside
project-scoped repository queries; graph materialization never loads all runs
and filters them in memory.

This amendment does not revive the Temporal execution model removed by issue
#1359 or the derivation, boundary, and architecture-model aggregates retired by
ADR-089/V199. A first-class work-item aggregate, retention policy, or
revision-stable projection-scope policy requires a separate decision.

## Amendment (issue #1435, 2026-07-26): live lifecycle observation

The issue #1359 amendment's statement that issue-thread ingestion is the sole
active write path is superseded. Issue #1435 adopts live `/implement`
lifecycle observation from the MCP tool layer while
`gc_workflow_run_ingest` remains the backfill and reconciliation path. This
does not make the read-model an executor: recording is a consequence of an
already-determined workflow transition and can never authorize, advance,
retry, or fail that transition.

The live path is governed by these invariants:

- Open the existing ADR-061 run, using its
  `(project, repo, issue_number, branch)` natural key, as soon as the canonical
  project/repository/issue/branch identity is available. The initial
  observation carries `started_at` and `final_state=RUNNING`; Phase E re-entry
  on the same issue branch refines the same run rather than creating another
  run identity.
- Emit only stable machine phase ids from the existing workflow/station
  vocabulary. A SKILL step number, display label, MCP tool name, or
  `next_action` string is not a phase id. `cycle_index` is present only when
  the authoritative transition owner knows the attempt order, and duration is
  measured from tool-layer timestamps rather than agent prose or reconstructed
  guesses.
- `READY_FOR_REVIEW` is an open, paused state and has no `ended_at`.
  Successful merge/close, explicit abandonment, supersession, and a
  non-recoverable failed run are terminal observations and carry `ended_at`.
  A failed phase attempt is not automatically a failed run: retryable gate,
  CI, review, or network failures remain phase events while the run stays
  open. Because the existing closed run-state vocabulary does not distinguish
  non-recoverable failure from abandonment or escalation, `FAILED` is added as
  a run state; it must be kept synchronized across the versioned contract,
  backend/MCP enums, REST documentation, and frontend type/badge vocabulary.
- Live observations can race with one another and with reconciliation. The
  `WorkflowTelemetryService` transaction boundary must merge them atomically:
  preserve the earliest `started_at`, reject `ended_at < started_at`, prevent
  an open observation from overwriting a terminal observation, and prevent
  lost updates to correlation/economics fields. A delayed `RUNNING` write or
  stale issue-thread ingest must never reopen a completed run. These are
  projection-consistency rules, not workflow transition authority.
- Live observation is strictly fail-open and happens after the workflow
  operation has determined its own result. Backend unavailability,
  authentication failure, validation failure, conflict, timeout, or malformed
  telemetry response may produce only a bounded diagnostic containing safe
  identifiers and a stable failure class; it must not alter the workflow
  result or its `next_action`. No telemetry payload, credential, raw issue
  body, response body, or stack trace is logged.
- Provenance continues to name the source fact, not merely the delivery time.
  A live write may use `ISSUE_THREAD` only when it is emitted from the same
  successful tool-layer operation that established the canonical durable
  issue record. Backfill retains `ISSUE_THREAD`; `MANUAL_IMPORT` remains
  economics-only and `TEMPORAL_VISIBILITY` remains historical. A tool-local
  fact with no issue-thread source must not be mislabeled and requires a
  separately decided closed provenance value.
- A live producer records the boundary that just occurred; it must not invoke
  the full issue-thread ingest after every boundary. Reconciliation must
  converge with live data rather than append a second copy of the same
  logical phase attempt. The append-only event needs a deterministic source
  identity/idempotency seam that survives retries and lets live emission and
  `gc_workflow_run_ingest` identify the same fact. Provenance, timestamp, or
  `(phase, event_type, cycle_index)` alone is not a safe deduplication key.

The two values this amendment leaves open are decided here:

- The closed provenance vocabulary gains `LIVE_EMISSION` for a tool-local fact
  with no issue-thread source. `ISSUE_THREAD` continues to name a fact carried
  by the durable issue record, so the two remain distinguishable in the store
  rather than only in the emitter's intent.
- The deterministic source identity is `workflow_phase_event.source_id`, unique
  per `(run_id, source_id)` and derived as `phase:eventType:cycleIndex` when the
  emitter cannot attest it. Re-recording an existing identity returns the stored
  event, so live emission and `gc_workflow_run_ingest` converge on one row per
  logical attempt. The service also assigns the attempt ordinal from durable
  history for a `STARTED` event and `0` for any other unordered event, which is
  what makes an unordered backfill land on the first live attempt instead of
  appending a phantom retry.

The terminal transitions the tool layer actually observes are: `MERGED` when
the post-merge phase completes, `CLOSED` with `CLOSED_WITHOUT_MERGE` when the
linked PR is seen closed unmerged, and `SUPERSEDED` when a new live attempt
opens on a different branch of the same work item. That last one is the only
abandonment observable at the moment it happens, because an agent that stops
working emits nothing. `FAILED` is written only from an explicit terminal
observation, never inferred from a retryable phase failure.

This amendment guarantees closure for terminal paths the tool layer can
observe. An abrupt process or host death cannot execute a terminal write;
without a lease/heartbeat, `RUNNING` therefore means "no terminal observation
has been recorded," not proof that a process is currently alive. Strict
liveness and stale-run reaping require a separate lease/heartbeat decision and
must not be simulated with a timer in skill prose or by treating telemetry as
workflow authority.

## Relationship to other ADRs

- **ADR-028** (Temporal boundary, superseded #1359): originally specified
  Temporal Visibility as the eventual source of truth; per the amendment
  above, this model's `ISSUE_THREAD` provenance is now the permanent path,
  not a projection of a Temporal system that was never built.
- **ADR-029** (issue-thread gate model): issue-thread records remain the durable
  workflow/audit record during the bridge; this surface reads from them, it does not
  replace them.
- **ADR-036** (per-step JSONL telemetry): distinct scope, local bridge economics
  measurement, not the product source of truth.
- **ADR-059** (MCP tool usage telemetry): the persistence/aggregation precedent
  (append-only, DB-side aggregation, no Envers). Distinct scope: one counts MCP tool
  calls, this explains run/phase/gate outcomes and economics.
- **ADR-026** (REST access control): endpoints inherit the bearer/session chain; the
  cross-project rollup is additionally `ROLE_ADMIN` in `ApiPathMatrix`.
- **ADR-035** (MCP tool catalog): the two project-scoped read paths join the
  `gc_query` allowlist + README/ADR drift surfaces.
- **ADR-084** (context-graph authority and time): requires the ontology bindings,
  audited projection sources, and Envers revision semantics adopted by the
  issue #1311 amendment.
