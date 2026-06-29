# ADR-072: Research REST and MCP Tool Surface

## Status

Accepted

## Date

2026-06-29

## Context

Issue #1004 asks Ground Control to expose research workflow and artifact
operations through the same API/MCP pattern used by the rest of the product.
The issue intersects four existing research contracts:

- ADR-064 owns `ResearchRun`, stage legality, gate policy, artifact manifests,
  checkpoint/resume, and project-scoped lifecycle state.
- ADR-065 owns the bounded observability snapshot for `GC-RSCH-N011`.
- ADR-069 owns the research provenance ledger.
- ADR-071 owns interoperability and source identity boundaries.

The remaining design risk is adapter drift. A REST controller, MCP handler, UI,
or agent-facing read tool could become a second place that decides lifecycle
legality, validates research content, records actors, parses workspace files,
calls providers, or builds a different observability shape. That would break the
run-scoped state model and weaken the shared security, validation, audit,
error, and MCP curation layers.

## Decision

### 1. REST is the public product boundary; services remain authoritative

Research routes live under `/api/v1/research-runs/**` and route through thin
controllers to research services. Controllers resolve the project with
`ProjectService`, bind request DTOs with Bean Validation/Jackson, and translate
domain objects into response DTOs. Controllers do not call repositories, parse
workspace files, perform provider calls, inspect local artifacts, or decide
stage/gate legality.

Research services own semantic validation: project type, project/run scope,
stage transition legality, gate decisions, artifact readiness, source counts,
bounded failure observations, usage/cost observations, provenance write
legality, idempotency, and same-run reference checks.

### 2. MCP is an adapter over REST, not a parallel domain layer

MCP research writes are curated action-discriminated tools over the REST
contract. The accepted surface is:

- `gc_research_run` for the `ResearchRun` lifecycle, gates, artifacts,
  observability snapshot, bounded errors, usage/cost, review comments,
  rationale, and disclosure actions.
- `gc_research_provenance` for run-scoped provenance node/edge writes and
  discoverable provenance reads.
- `gc_query` for pure GET reads under the existing `/api/v1/research-runs`
  allowlist.

MCP handlers may validate required tool arguments, map snake_case tool fields to
the REST DTO wire shape, and call `mcp/ground-control/lib.js` request helpers.
They must not duplicate lifecycle, gate, artifact, source, provenance, cost, or
observability decisions.

### 3. Reads and writes stay deliberately different

`gc_query` remains GET-only, allowlisted, header/body-free, path-normalized, and
body/timeout bounded per ADR-035. It is appropriate for ad hoc reads of research
run state, snapshot state, artifacts, gates, decision logs, rationale,
disclosure, and provenance records.

State-changing research operations require curated MCP actions or REST calls.
Do not add method/body/header knobs to `gc_query`, and do not use `gc_query` as a
hidden write tunnel.

### 4. One backend contract feeds API, MCP, and UI

The `ResearchRunSnapshot` from ADR-065 is the N011 observability contract. The
UI and MCP must consume the backend snapshot or the underlying run-scoped REST
reads. They must not re-derive current stage, pending gates, artifact readiness,
source counts, access gaps, errors, or cost from frontend conditionals,
workspace file scans, skill transcripts, `workflow_phase_event`, local logs, or
provider payloads.

API-visible enum mirrors and MCP Zod enum arrays follow ADR-034. MCP write body
field allowlists and per-action required fields are mirrors of the OpenAPI/DTO
contract and belong in the existing OpenAPI/MCP drift-test inventory.

### 5. Security, actor, and error surfaces stay shared

All research API paths inherit the shared ADR-026 `/api/v1/**` authenticated
rules unless a future cross-project or operator-only route is explicitly added
to `ApiPathMatrix`. Run-scoped reads and writes must conceal cross-project and
cross-run misses as `404` through project-scoped repository/service lookups.

Mutation actors come from `ActorFilter` / `ActorHolder`; request DTOs and MCP
schemas must not accept caller-supplied audit actors. Domain provenance fields
may identify an adapter, source action, or reviewer role, but they do not
authenticate the caller.

Transport errors use `GroundControlException` subclasses through
`GlobalExceptionHandler` and `ErrorResponse`. Research failure observations are
bounded product facts on the run, not exception envelopes, stack traces, raw
provider errors, or leaked request/response bodies.

### 6. Sensitive content and side effects stay out of adapter payloads

Research API, MCP, graph, telemetry, errors, and logs may carry bounded
identifiers, enum names, counts, hashes, locators, short summaries, timestamps,
and stable error codes. They must not carry prompts, completions, manuscript
prose, PDFs, full text, raw charting rows, raw search results, raw provider
payloads, bearer tokens, Zotero secrets, Git credentials, or private absolute
paths.

This surface introduces no new subprocess, shell-out, provider call, GitHub
write, citation call, token-in-argv path, or secret-bearing configuration.
Provider, citation, Git, filesystem, or orchestration side effects remain at the
ADR-028/ADR-055/ADR-071 adapter boundaries and re-enter the backend through
structured research service commands.

### 7. Extensibility seam

The extension seam is the adapter inventory, not a new adapter framework:

- add a new action to `gc_research_run` when it mutates or reads the
  `ResearchRun` aggregate family;
- add a new action to `gc_research_provenance` when it mutates or reads the
  provenance ledger;
- add only a new named tool when a new research aggregate, privileged admin
  boundary, compute-heavy operation, or non-run-scoped surface has a materially
  different contract;
- add pure GET routes to the `gc_query` allowlist only when ad hoc reads are
  appropriate and response size/leakage bounds are understood.

Each new public enum, DTO mirror, body-field allowlist, action discriminator, or
allowlisted read path must update the existing drift checks and documentation
surface that already cover MCP/OpenAPI and `gc_query`.

## Consequences

### Positive

- Issue #1004 gets a single adapter boundary instead of separate REST, MCP, UI,
  and agent-side state machines.
- N011 observability stays tied to ADR-065's bounded snapshot and does not drift
  into workflow telemetry or workspace parsing.
- The design reuses existing auth, validation, error, actor, logging, MCP
  curation, OpenAPI drift, and controller-slice testing conventions.

### Negative

- Adding research operations requires updating several mirrored adapter
  inventories: REST docs/OpenAPI, MCP action descriptions, Zod enum arrays,
  body-field allowlists, and drift tests.
- Some read operations appear both as discoverable MCP actions and through
  `gc_query`. This is accepted for high-frequency research reads, but the REST
  backend remains the source of truth for both paths.

### Risks

- If MCP handlers or frontend code reimplement lifecycle checks, they can
  disagree with `ResearchRunService` and show or perform illegal transitions.
- If `gc_query` is widened to accept methods, headers, or bodies, it becomes a
  write bypass around curated tool schemas.
- If raw research content is copied into adapter payloads, logs, errors, graph
  properties, or telemetry, unpublished research material and credentials can
  leak outside the run scope.
- If enum/action/body-field mirrors are not added to drift tests, MCP tools can
  silently accept or omit fields that the backend contract does not.

## Non-Goals

- No implementation of controllers, DTOs, services, migrations, MCP tools,
  frontend views, provider adapters, source ledgers, or graph contributors in
  this ADR.
- No replacement of ADR-064 lifecycle/gate/artifact rules, ADR-065
  observability snapshot, ADR-069 provenance ledger, or ADR-071 source identity
  boundary.
- No generic workflow engine, approval engine, adapter framework, observability
  platform, OpenTelemetry/Prometheus decision, or full-text/source store.
- No new authentication model, actor override mechanism, exception hierarchy,
  error envelope, logging stack, policy runner, GitHub side-effect path, or
  token-in-argv path.

## Related Requirements

- `GC-RSCH-F003` - explicit state machine and upstream artifact gating.
- `GC-RSCH-N009` - interoperability.
- `GC-RSCH-N010` - extensibility.
- `GC-RSCH-N011` - observability.

## Related Issues

- #1004 - Research REST and MCP tool surface.
- #1000 - Research run lifecycle and phase gating.
- #1003 - Research graph projection and traversal support.
- #1028 - Research checkpointing, observability, and budgets.
- #1031 - Research workspace UI and dashboard.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-028 - Temporal Workflow Orchestration Boundary.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Catalog Curation.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-065 - Research Run Observability Snapshot.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-071 - Research Interoperability and Source Identity Boundary.
