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
