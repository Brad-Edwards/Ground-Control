# ADR-065: Research Run Observability Snapshot

## Status

Accepted

## Date

2026-06-25

## Context

`GC-RSCH-N011` requires users to see a research run's current phase, pending
gates, source counts, errors, access gaps, cost, and artifact readiness. ADR-064
defines the durable research lifecycle, gate policy, decision records,
checkpoint/manifest state, and the rule that lifecycle legality never comes
from workspace file existence or workflow telemetry.

That leaves a narrower design risk: an implementation could satisfy the UI by
assembling status from local files, skill transcripts, `workflow_phase_event`
rows, exception messages, or frontend-only logic. That would create a second
state model, leak sensitive research material, and make resume/gating disagree
with what users see.

Adjacent decisions must stay separate:

- ADR-055 owns the skill-side literature-review workflow and citation MCP.
- ADR-056 owns project-level research intake and budget caps.
- ADR-061 owns `/implement` workflow telemetry and economics reporting. It is a
  reporting projection, not research execution state.
- ADR-064 owns research-run lifecycle, gates, decisions, artifacts, checkpoints,
  retry boundaries, and run-stage legality.

## Decision

### 1. Observability is a bounded read snapshot over authoritative run state

Expose research observability as a run-scoped read snapshot composed from the
research domain's authoritative records. It is a view over business state, not a
new executor, not a second state machine, and not an independent persistence
surface that can advance a run.

The snapshot may be materialized by a service method, DTO, REST read, MCP read,
and frontend page, but the semantic source remains the same durable state used
by ADR-064:

- `ResearchRun` current stage/status and run-start snapshots;
- pending and completed gate records;
- stage artifact/checkpoint manifest records;
- run-scoped source/access-gap summary state accepted during lifecycle actions;
- run-scoped failure/error observations accepted during lifecycle actions;
- budget snapshots and explicit run-scoped usage/cost observations.

Reads must not scan workspace files, parse `decisions.md`, inspect local
transcripts, replay Envers, or infer stage legality from `workflow_run` /
`workflow_phase_event`.

### 2. Use `stage` in contracts; UI may label it as phase

ADR-064's `ResearchRunStage` enum is the API/domain contract. User-facing copy
may say "phase" when that is clearer, but DTOs, MCP payloads, frontend types, and
domain services should use `stage` / `currentStage` so the product lifecycle
does not drift back to skill phase numbers or telemetry phase strings.

API-visible enum mirrors follow ADR-034.

### 3. Pending gates are derived from gate state, not UI conditionals

The snapshot's pending-gate section is derived from persisted gate policy,
decision records, and current stage prerequisites. It carries bounded metadata:
gate point, related stage, status, policy basis, required/optional behavior,
created/updated timestamps, and safe actor/provenance identifiers.

It must not include raw prompts, full recommendations, manuscript prose, private
workspace paths, bearer material, Zotero secrets, provider payloads, or raw user
decision text beyond bounded summaries already accepted into the decision
record.

### 4. Artifact readiness is readiness of manifest state, not file content

Artifact readiness means "an active, non-superseded manifest/checkpoint record
satisfies the prerequisite matrix for the stage." It does not mean the backend
read the artifact content or that a local path currently exists.

The snapshot should report readiness per required artifact type and stage using
a small closed vocabulary such as ready, missing, blocked by gate, failed, or
superseded. The manifest may point at a workspace locator or external entity,
but readiness is computed from persisted lifecycle metadata.

### 5. Source counts and access gaps come from accepted source state

Source counts and access gaps are research lifecycle metadata. They are not
computed by reading `charting-data.csv` or `search-log.md` on every status read.

When a stage action accepts or replaces a search/source/screening/charting
artifact, the same transaction must persist bounded summary facts sufficient for
observability. The v1 minimum count families are:

- candidate sources;
- screened included sources;
- screened excluded sources;
- charted full-text sources;
- access gaps.

The two-state source rule from `docs/research/RESEARCH_WORKFLOW.md` remains
binding: a source is either fully in the review or an access gap; charted counts
must not include sources charted from abstract, memory, or unverified metadata.

The extensibility seam is the source disposition vocabulary and per-stage
summary policy. If later work needs source-level drill-down, add a run-scoped
source ledger or a document-backed reference under the research domain; do not
teach the status read to parse arbitrary workspace files.

### 6. Errors are product failure observations, not exception leakage

The snapshot's error section reports bounded run/stage failure observations:
stage, attempt, stable error code, retryability class, occurred timestamp,
bounded safe message/summary, and provenance. It must not expose stack traces,
exception class names as user contracts, raw provider errors, prompts,
completions, request bodies, PDF text, search-result bodies, headers, bearer
tokens, or local absolute paths.

API transport errors continue through `GroundControlException` subclasses,
`GlobalExceptionHandler`, and `ErrorResponse`. Do not create a research-specific
error envelope.

### 7. Cost separates budget caps from observed usage

Budget caps are run-start snapshots copied from `ResearchIntake`:
token cap, wall-clock cap, and USD micros cap. Observed usage/cost is a separate
run-scoped fact with units, currency/proxy semantics, provider/model when safe,
timestamp, and provenance.

Do not derive spend from hardcoded provider price tables in the domain service.
If pricing translation is needed later, it belongs behind a validated
configuration or imported usage/cost observation with explicit provenance.

ADR-061 `workflow_run.cost_proxy` may be displayed only as operational workflow
telemetry when explicitly correlated to the research run; it is not the
authoritative research budget ledger and cannot drive lifecycle gates.

### 8. One backend contract feeds REST, MCP, and UI

The web UI must consume the backend snapshot rather than re-deriving gate,
artifact, source, access-gap, cost, or error status from separate endpoint calls
or local heuristics. MCP reads should mirror the REST contract through the
existing curated/allowlisted query pattern; MCP writes, if added later, submit
structured lifecycle actions to the research service.

No agent sandbox, UI component, or MCP adapter may perform privileged GitHub,
citation, filesystem, or provider side effects merely to render observability.
Side effects belong at the ADR-028 orchestration/adapter boundary or in explicit
research lifecycle write actions.

### 9. Cross-cutting layers stay shared

- **Security and authorization:** snapshot reads stay under ADR-026 bearer /
  browser chains, resolve one project through `ProjectService`, and reject
  cross-project run access. Cross-project research observability, if added, is
  a separate admin-only surface.
- **Validation:** REST DTOs use Bean Validation and Jackson enum parsing; MCP
  mirrors use Zod and ADR-034 drift gates when API-visible enums are mirrored.
  Services own project type, project scope, run existence, stage/gate/artifact
  consistency, and bounded-summary validation.
- **Errors:** transport errors use existing domain exceptions and
  `ErrorResponse`; run failure observations use bounded product records.
- **Audit and actor provenance:** mutation records derive authenticated actor
  context from `ActorFilter` / `ActorHolder`. Clients do not supply audit actors.
- **Logging:** use SLF4J with low-cardinality fields: project, run ID, stage,
  gate point, artifact type, source disposition, error code, and provenance.
  Never log prompts, manuscripts, PDFs, raw source rows, provider payloads,
  bearer tokens, Zotero secrets, or private absolute paths.
- **Configuration and OS exposure:** this read surface introduces no secrets,
  subprocesses, shell-outs, token-in-argv path, or external network call. Later
  provider/citation/orchestration integrations must use validated
  `@ConfigurationProperties` or the ADR-028 activity boundary.
- **Testing:** controller reads need `@WebMvcTest` slices, service composition
  needs unit tests over each blocker/count/cost/error state, API-visible enums
  need ADR-034 coverage, and repo completion still runs `make policy`.

## Consequences

### Positive

- Users get one consistent run status surface instead of a UI that stitches
  together local files, telemetry, and lifecycle state.
- N011 observability reuses ADR-064 lifecycle state without weakening the stage
  and gate invariants.
- Sensitive research content stays out of logs, errors, telemetry, MCP payloads,
  and broad list responses by default.

### Negative

- Stage actions that accept source sets, access gaps, costs, artifacts, or
  failures must persist bounded summary metadata when the action is accepted.
  That is more deliberate than reading workspace files later, but it is the only
  way to make resume and observability agree.
- A single snapshot response can become large if future work adds source-level
  drill-down. Detailed source rows should move behind explicit paged drill-down
  reads, not into the default status snapshot.

### Risks

- If implementation treats missing summary state as zero instead of unknown,
  users can see false assurance about source coverage or access gaps.
- If `workflow_phase_event` or frontend conditionals drive pending-gate status,
  the display can diverge from the lifecycle service and unblock work
  incorrectly.
- If errors or source/access details are copied directly from provider payloads
  or artifacts, unpublished research content and secrets can leak through a
  status endpoint.

## Non-Goals

- No implementation of controllers, DTOs, migrations, MCP tools, frontend views,
  source ledgers, or cost importers in this ADR.
- No replacement of ADR-064's lifecycle model, gate policy, artifact manifest,
  or checkpoint rules.
- No generic observability platform, OpenTelemetry/Prometheus decision, or
  workflow execution engine.
- No full-text, PDF, manuscript, charting-row, or search-result storage
  decision.
- No new authentication model, actor override mechanism, error envelope,
  logging stack, or policy runner.

## Related Requirements

- `GC-RSCH-N011` - observability.
- `GC-RSCH-F003` - explicit state machine and upstream artifact gating.
- `GC-RSCH-F036` - checkpoint/resume after material actions.
- `GC-RSCH-N007` - reliability.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-028 - Temporal Workflow Orchestration Boundary.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-061 - Workflow-Run Telemetry and Economics Reporting Surface.
- ADR-064 - Research Run Lifecycle and Stage Gating.
