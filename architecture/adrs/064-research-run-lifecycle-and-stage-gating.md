# ADR-064: Research Run Lifecycle and Stage Gating

## Status

Accepted

## Date

2026-06-25

## Context

Issue #1000 and requirements `GC-RSCH-R001` / `GC-RSCH-R003` require Ground
Control to distinguish methodology selection, protocol planning, source search,
screening, charting, synthesis, argument construction, and prose drafting as
separate lifecycle stages, while also supporting autonomous and copilot modes
with configurable human gates at method, protocol, search, synthesis, and
writing decisions. The issue also pulls in checkpoint/resume, reliability, and
observability concerns: a downstream stage must not start when required upstream
artifacts are missing, a stopped or failed run must resume without duplicating
completed artifacts, and state must be visible through API/MCP reads.

The repository already has adjacent decisions that must stay separate:

- ADR-055 ships the skill-side research workflow and citation MCP. Its skills
  currently use five human-facing phases; the search skill internally performs
  source search, screening, charting, and synthesis.
- ADR-056 introduces `ProjectType.RESEARCH` and `ResearchIntake` as project-level
  intake/defaults. It explicitly leaves the run lifecycle to later work.
- ADR-028 warns against building a second generic workflow engine beside
  Temporal, and ADR-061 defines `workflow_run` / `workflow_phase_event` as
  operational telemetry, not execution state.
- ADR-049 shows the precedent for a dedicated execution aggregate that snapshots
  run inputs and keeps authored/planned objects separate from runtime evidence.
- ADR-045 defines `EvidenceArtifact` as summarized GRC evidence. Research
  workflow files such as `requirements.md`, `lit-review-plan.md`, `search-log.md`,
  `charting-data.csv`, `synthesis.md`, `argument-map.argdown`, and
  `manuscript.tex` are lifecycle checkpoint artifacts unless deliberately
  promoted into that summarized-evidence domain.

Without an explicit decision, likely failure modes are:

- adding run status fields to `ResearchIntake`, making mutable project defaults
  drive in-flight and historical runs;
- reusing `workflow_phase_event.phase` strings as the research lifecycle model,
  turning telemetry into an executor;
- treating workspace-local file existence as the gate source of truth;
- treating workspace-local `decisions.md` as the durable decision log instead
  of a persisted run-scoped record;
- modeling research human gates as requirements `QualityGate` rows or generic
  workflow telemetry events, which would conflate different "gate" concepts;
- treating `AutonomyLevel.AUTONOMOUS` as permission to skip method- or
  policy-mandatory human decisions;
- hiding the four evidence-base stages inside one "search" state, which would
  fail `GC-RSCH-R001`;
- duplicating validation, error envelopes, actor provenance, enum mirrors, or MCP
  write semantics rather than using the repo's existing cross-cutting layers.

## Decision

### 1. `ResearchRun` is a separate, project-scoped execution aggregate

Introduce a dedicated `ResearchRun` aggregate under the existing research domain
boundary. It is a sibling of `ResearchIntake`, not a field on `Project` and not a
subclass of `WorkflowRun`.

`ResearchIntake` remains the project-level default/intake record. A run reads the
intake when it is started and snapshots the run-driving values that must remain
stable for resume and audit: intended output, autonomy level, allowed tools,
privacy constraints, and budget caps. Later edits to `ResearchIntake` do not
rewrite active or completed runs; starting a new run is how new intake defaults
take effect.

`ResearchRun` is durable business state, so it follows the repository pattern for
audited aggregates: `BaseEntity`, project-scoped repository queries, Flyway
migrations plus audit shadow tables, and service-owned transactions.

### 2. Stage vocabulary is a closed research enum, not a telemetry string

The product lifecycle uses a closed `ResearchRunStage` enum with these eight
values:

- `METHODOLOGY_SELECTION`
- `PROTOCOL_PLANNING`
- `SOURCE_SEARCH`
- `SCREENING`
- `CHARTING`
- `SYNTHESIS`
- `ARGUMENT_CONSTRUCTION`
- `PROSE_DRAFTING`

The domain/API should prefer `stage` / `currentStage` naming. This intentionally
does not reuse `workflow_phase_event.phase`, the skill docs' phase numbers, or
generic workflow terms. The existing skill "phase 3" maps to four product
stages: source search, screening, charting, and synthesis.

Any API-visible enum mirrors for frontend or MCP must follow ADR-034. Adding a
new lifecycle stage later is an enum/API contract change, not free text.

### 3. Run status is separate from stage identity

The current stage answers "where in the research lifecycle is this run?" Run
status answers "what is happening to that stage or run?" They must be separate
fields. A run can be in `SYNTHESIS` and be `IN_PROGRESS`, `BLOCKED`, `STOPPED`,
or `FAILED`.

The implementation must centralize the transition graph in the domain service,
not in controllers, MCP handlers, or frontend conditionals. Downstream starts
are valid only when every required predecessor stage is completed and every
required predecessor artifact is present in the durable manifest, and no required
human gate is pending.

Completed stages are immutable for gating purposes. Rework uses an explicit new
attempt or replacement record with rationale; it must not silently mutate the
artifact that made a downstream stage legal.

### 4. Human gates are run-scoped decision policy, not generic quality gates

Human-gate configuration is research execution policy. It belongs under the
research domain beside `ResearchRun`, not in the requirements `qualitygates`
domain, not in `workflow_phase_event.phase`, and not in skill prompt text.

Each run resolves and snapshots a `ResearchGatePolicy` at run start. The policy
starts from the run's `AutonomyLevel` snapshot and applies explicit gate
overrides. Later edits to project intake or future project-level defaults do not
retroactively change active or completed runs.

The policy uses a closed `ResearchGatePoint` vocabulary:

| Gate point | Lifecycle location |
|---|---|
| `METHOD_DECISION` | methodology selection result before protocol planning |
| `PROTOCOL_DECISION` | protocol/plan approval before source search |
| `SEARCH_DECISION` | search strategy or source-set decision before screening/charting proceeds |
| `SYNTHESIS_DECISION` | synthesis/evidence-base decision before argument construction |
| `WRITING_DECISION` | argument/drafting posture before prose drafting |

Each gate point resolves to one of three behaviors:

- require a human decision before the lifecycle can proceed;
- allow autonomous use of the declared default/recommendation while still
  recording the decision; or
- disabled for that run, unless the point is mandatory under the selected method
  or policy.

`COPILOT` defaults to requiring human decisions at the five gate points.
`AUTONOMOUS` defaults to autonomous use of declared defaults/recommendations, but
it cannot bypass a gate marked mandatory by the run policy, selected methodology,
privacy/budget policy, or a later compliance rule. Per-run overrides may require
more human gates than the default mode, but must not silently relax mandatory
gates.

The extension seam is the policy resolver: it takes the intended output/method
profile, autonomy level, and explicit gate overrides, then emits the resolved
per-run policy. Adding method-specific gate rules later should be an inventory or
policy-entry change in this resolver, not scattered conditionals in controllers,
MCP handlers, or frontend components.

### 5. Gate decisions are durable records, not `decisions.md`

Every required or autonomous gate outcome creates a durable run-scoped decision
record. A pending required gate blocks the relevant stage transition until the
decision is recorded. Autonomous decisions still create records so resume,
observability, and audit can explain why the run moved forward without a user
click.

The decision record is lifecycle metadata, not a content store. It must carry at
least the owning run, gate point, related stage/attempt, recommendation or option
identifier, bounded rationale/summary, decision outcome, policy basis, actor or
provenance, and timestamps. It must not store raw prompts, manuscript prose,
full search results, charting rows, PDF text, bearer tokens, Zotero secrets, or
private absolute workspace paths.

Workspace `decisions.md` can remain an exported or mirrored research artifact,
but it is not the authority for gate completion. API and MCP reads derive pending
gates and decision history from persisted Ground Control state.

### 6. Stage completion is proven by durable artifact/checkpoint state

The gate source of truth is Ground Control's persisted run state, not local file
existence and not skill prose. Each material output that can unblock a later
stage must be represented by a durable run-scoped artifact/checkpoint record
with at least:

- the owning `ResearchRun`;
- `ResearchRunStage`;
- a closed artifact type;
- creation/completion timestamp;
- actor/provenance;
- optional workspace locator or external identifier;
- optional content hash or digest when the artifact is file-backed;
- replacement/supersession pointer or attempt identifier when rework occurs.

This record is a lifecycle manifest, not necessarily the artifact content. Large
or sensitive research artifacts can remain in the user's workspace or a later
document store; the manifest is what the lifecycle service validates. If a
research output is later promoted to a first-class `Document` or
`EvidenceArtifact`, the manifest points at that entity rather than duplicating
its schema.

The initial prerequisite matrix is:

| Stage | Required predecessor evidence |
|---|---|
| `METHODOLOGY_SELECTION` | started `ResearchRun` with a RESEARCH project and captured intake snapshot |
| `PROTOCOL_PLANNING` | methodology requirements artifact from `METHODOLOGY_SELECTION` |
| `SOURCE_SEARCH` | protocol/plan artifact from `PROTOCOL_PLANNING` |
| `SCREENING` | candidate-source/search-log artifact from `SOURCE_SEARCH` |
| `CHARTING` | screened included/excluded source set from `SCREENING` |
| `SYNTHESIS` | charting data/coding evidence from `CHARTING` |
| `ARGUMENT_CONSTRUCTION` | synthesis/evidence-matrix artifact from `SYNTHESIS` |
| `PROSE_DRAFTING` | argument outline/map artifact from `ARGUMENT_CONSTRUCTION` |

The matrix belongs in one domain-owned policy surface so autonomous/copilot gate
rules, method-specific variants, or taxonomy-specific source roles can be added
without scattering conditionals.

### 7. Every material action creates a transactionally durable checkpoint

`GC-RSCH-F036` requires resume after every material action. For this lifecycle,
a material action is any accepted mutation that changes what the next safe
research operation is allowed to do:

- starting a run or stage attempt;
- stopping or failing a run/stage;
- recording, replacing, or superseding a lifecycle artifact/checkpoint;
- opening, auto-resolving, or human-resolving a gate decision;
- completing a stage;
- resuming a stopped or failed run.

The same service transaction that accepts one of these actions must write the
durable run state, gate-decision state, or artifact/checkpoint manifest row that
makes the action visible to later API/MCP reads. A checkpoint must never depend on
a post-hoc filesystem scan, local workspace file existence, Envers replay,
workflow telemetry ingestion, or a skill transcript. If the transaction rolls
back, the action did not happen from the lifecycle's point of view.

Checkpoint records are lifecycle metadata. They carry bounded, resumable facts:
run, stage, attempt, action/checkpoint type, actor or provenance, timestamp,
status/outcome, optional idempotency key or source action id, and references to
artifact records or external entities. They must not store raw prompts,
manuscript prose, search-result bodies, charting rows, PDFs, bearer tokens,
provider secrets, Zotero secrets, or private absolute workspace paths.

Read-only API/MCP calls, UI selection/cursor movement, transient agent narration,
local draft file saves that are not declared as lifecycle artifacts, logs, and
`workflow_phase_event` telemetry are not material actions for resume. They may
improve observability, but they must not advance the lifecycle frontier.

The idempotency seam is the run-scoped material-action identity. External callers
that may retry (MCP tools, future orchestrators, or UI autosave) should submit a
bounded opaque idempotency key or source action id; internal service paths may use
the natural unique identity of the run/stage/attempt/artifact/gate action. The
domain service and database constraints then either return/reuse the existing
checkpoint or raise a `ConflictException` for a genuinely incompatible retry.

### 8. Checkpoint/resume is idempotent domain behavior, not Envers replay

Envers answers who changed rows and when; it is not the business resume model.
Resume must inspect `ResearchRun`, stage state, artifact/checkpoint records, and
attempt identifiers, pending gate records, and recorded gate decisions. Retrying
a stopped or failed stage must be idempotent: if a completed predecessor artifact
or gate decision already exists, the retry reuses it unless the caller explicitly
records a replacement/rework rationale.

The unique/idempotency boundary belongs on run-scoped stage/artifact identity,
for example by ensuring one active completion artifact of a given required type
per run/stage/attempt. Concurrency races should fail as `ConflictException` or
be merged by an explicit idempotent upsert, following the existing service plus
database-constraint pattern.

### 9. Retry, timeout, and partial-failure policy stays at the right boundary

The research lifecycle service records durable state; it does not sleep,
backoff, launch skills, poll providers, shell out, or retry external side
effects inline. Retry and timeout policy belongs at the boundary that performs
the effect: future Temporal activities, MCP adapters, citation/full-text
adapters, or other infrastructure ports. Those callers re-enter the domain
service with the same run-scoped material-action identity so retries reuse the
existing checkpoint or fail as a real conflict.

Retry classification must be explicit:

- domain validation failures, unauthorized access, project-scope misses,
  missing prerequisites, mandatory pending gates, and incompatible idempotency
  conflicts are not retryable without changing the request or policy state;
- transient provider/network failures such as timeouts, connection resets, HTTP 429 responses,
  and 5xx responses may be retried by the boundary adapter within a bounded
  budget;
- permanent provider failures are recorded as failed or blocked attempts with
  a bounded reason, not retried as if they were infrastructure noise.

Timeouts must be named contracts, not scattered literals. Product budgets
captured from `ResearchIntake` are snapshot onto `ResearchRun`; adapter-level
connect/read/activity timeouts are configuration concerns owned by validated
`@ConfigurationProperties` or, for future Temporal work, activity options. Do
not pass secrets or private workspace paths through timeout/retry config, logs,
process argv, Temporal history, or telemetry.

Partial-failure recovery uses the same checkpoint model as normal progress. If
one material action succeeds and a later external action fails, the successful
action remains visible through its persisted checkpoint/manifest/decision row,
and the run/stage moves to `STOPPED`, `FAILED`, or `BLOCKED` with enough bounded
metadata to resume from the last safe frontier. The implementation must not roll
forward from a local file, transcript, log line, or telemetry event that lacks a
corresponding durable lifecycle checkpoint.

### 10. API, MCP, and visibility reuse the existing product surfaces

REST endpoints belong under `/api/v1/**`, route through controllers to the
research service, and return DTOs shaped from domain objects. Controllers do not
call repositories and do not parse workspace files.

MCP read/write affordances must mirror the REST contract through the existing MCP
curation and drift-check patterns. If a curated write tool is added, it submits
structured actions such as start run, start stage, complete stage, stop, fail,
resume, open/resolve gate, record decision, and record artifact; it must not
perform privileged filesystem, GitHub, or citation side effects from the agent
sandbox. Reads can route through `gc_query` once allowlisted.

`workflow_run` / `workflow_phase_event` from ADR-061 may record observability
about research-run operations, but never drive stage legality.

### 11. Cross-cutting layers stay shared

- **Security and authorization:** routes stay inside the ADR-026 bearer/session
  chains and `ApiPathMatrix`. Project-scoped reads and writes resolve the project
  through `ProjectService`; cross-project summaries, if added, are admin-only.
- **Input validation:** request DTOs use Bean Validation and Jackson enum
  parsing. Services own project type checks, same-project lookup, stage
  transition legality, resolved gate-policy validation, mandatory-gate
  enforcement, pending-gate checks, artifact prerequisite checks, idempotency,
  and replacement/rework rules.
- **Error envelopes:** use `DomainValidationException`, `ConflictException`, and
  `NotFoundException` through `GlobalExceptionHandler` and `ErrorResponse`. Do
  not hand-roll research-specific error JSON.
- **Actor provenance and audit:** mutation actor comes from `ActorFilter` /
  `ActorHolder`; clients do not supply owner/actor fields for audit. Business
  owner/assignee fields may exist, but they do not authenticate the caller.
- **Logging:** use SLF4J with low-cardinality events and IDs: project, run ID,
  stage, status, gate point, decision status, artifact type, attempt. Do not log
  prompts, manuscripts, raw charting rows, search results, bearer tokens, Zotero
  keys, PDFs, or absolute private workspace paths.
- **Configuration and OS/runtime exposure:** this lifecycle work introduces no
  new secrets, subprocesses, shell-outs, or token-in-argv path. If a later
  orchestrator launches skills or citation tools, that requires the ADR-028
  workflow/activity boundary and secret-safe configuration, not domain-service
  process execution.
- **Testing and policy:** controller surfaces need `@WebMvcTest` slices, service
  transition/gating rules need unit tests, migrations need smoke coverage, and
  API-visible enum mirrors need ADR-034 coverage. Repo work still completes
  through `make policy`.

## Consequences

### Positive

- `GC-RSCH-R001` gets an eight-stage product lifecycle instead of inheriting the
  five skill phases or generic telemetry phases.
- `GC-RSCH-R003` gets an explicit run-scoped human-gate policy and durable
  decision record instead of relying on skill prose or workspace files.
- Research run state can be resumed and inspected through Ground Control without
  trusting local files as the authority.
- `ResearchIntake` remains clean project defaults; `ResearchRun` owns execution
  state and snapshots the values and gate policy that affect a run.
- The design reuses existing project scoping, validation, exception handling,
  audit actor provenance, enum-drift gates, logging, repository/service
  boundaries, and controller-test conventions.

### Negative

- The product lifecycle is more granular than the current skill entrypoints.
  The adapter that records skill progress must map one skill invocation to
  multiple product stages when source search, screening, charting, and synthesis
  happen in sequence.
- Artifact manifest state is another durable surface. It is necessary for
  gating/resume, but the implementation must keep it as lifecycle metadata and
  avoid turning it into a duplicate document/evidence store.
- Human-gate policy and decision records add another durable surface. That is
  required for autonomous/copilot correctness, but the implementation must keep
  it research-specific and avoid creating a generic approval engine.

### Risks

- If implementation gates on workspace-local file paths, a moved or partially
  written file can unblock downstream work incorrectly.
- If implementation reuses `workflow_phase_event.phase`, reporting data can
  become an accidental executor and diverge from the business state machine.
- If completed artifacts are overwritten in place, resume can duplicate or lose
  prior work and the audit trail will not explain which artifact made a
  downstream stage legal.
- If autonomous mode is interpreted as "skip all gates," method-, privacy-, or
  policy-mandatory gates can be bypassed without a durable rationale.
- If `decisions.md` is treated as the authority, a moved or manually edited
  workspace file can make Ground Control believe a human decision occurred.
- If research artifact content leaks into errors, logs, telemetry, or broad list
  responses, unpublished manuscripts, private search strategy, Zotero metadata,
  or citation payloads can escape the intended project scope.

## Non-Goals

- No implementation of the lifecycle, migrations, controllers, MCP tools,
  frontend views, orchestration workers, or artifact storage in this ADR.
- No replacement of ADR-055's skills or citation MCP.
- No Temporal workflow implementation and no generic workflow engine.
- No generic approval engine and no reuse of the requirements `QualityGate`
  aggregate for research human decisions.
- No change to `ResearchIntake` beyond treating it as run-start input.
- No new authentication model, actor override mechanism, error envelope, enum
  mirror system, logging stack, or policy runner.
- No full-text/PDF/manuscript storage decision; this ADR only requires a durable
  manifest sufficient for gating and resume.

## Related Requirements

- `GC-RSCH-R001` - distinguish research lifecycle stages.
- `GC-RSCH-R003` - autonomous/copilot modes and human gates.
- `GC-RSCH-F003` - explicit state machine and upstream artifact gating.
- `GC-RSCH-F036` - checkpoint/resume after material actions.
- `GC-RSCH-N007` - reliability.
- `GC-RSCH-N011` - observability.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-028 - Temporal Workflow Orchestration Boundary.
- ADR-029 - Issue-Thread Gate Model.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Catalog Curation.
- ADR-045 - Evidence Derivation and Temporal State History.
- ADR-049 - Test Run Entity.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-061 - Workflow-Run Telemetry and Economics Reporting Surface.
