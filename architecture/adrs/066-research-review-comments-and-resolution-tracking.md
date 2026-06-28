# ADR-066: Research Gate Decision Log and Review Comments

## Status

Accepted

## Date

2026-06-28

## Context

Issue #1001 places `GC-RSCH-R003` beside `GC-RSCH-F004`,
`GC-RSCH-F034`, `GC-RSCH-N012`, and `GC-RSCH-N013`. The research-run lifecycle
must support configurable gates at method, protocol, search, synthesis, and
writing decisions, and those gates must expose recommendation, rationale,
decision, actor, timestamp, later reviewability, and human-accountability
provenance.

ADR-064 already establishes `ResearchRun`, the stage-transition model, the five
`ResearchGatePoint`s, run-scoped gate policy, artifact checkpoints, and the rule
that workspace `decisions.md` is not the durable authority. ADR-065 establishes
bounded run observability. The remaining design risk is narrower: the
implementation could satisfy stage blocking while conflating gate state,
decision history, recommendation provenance, and human review comments.

Likely failure modes are:

- treating one current gate-state row as the full decision log;
- using Envers history as the product decision log, making audit replay carry
  business semantics;
- expanding a gate rationale field into a mixed recommendation, decision, and
  review-comment log;
- treating GitHub issue comments, PR review comments, or workspace
  `decisions.md` as the product's research review state;
- creating a generic approval/comment system that is not anchored to the
  research run lifecycle;
- letting unresolved comments silently become a second gate outside the
  `ResearchRunService` transition rules;
- accepting request-body actor fields for decision, comment author, or resolver
  identity;
- collapsing agent-authored recommendations and human approvals into one actor
  string, hiding accountability;
- storing raw manuscript prose, prompts, PDFs, search-result bodies, charting
  rows, private workspace paths, bearer tokens, Zotero secrets, or provider
  payloads in gate records, review comments, logs, errors, telemetry, or broad
  snapshots.

## Decision

### 1. Gate state, decision history, and review comments are distinct

The research domain keeps a run-scoped gate-state concept whose job is to answer
"does this gate currently block the guarded stage exit?" It is one current
policy/current-state row per run and `ResearchGatePoint`, resolved from the
run's autonomy snapshot and explicit overrides per ADR-064.

The research domain also needs a run-scoped decision-log concept whose job is to
answer "what question, recommendation, rationale, decision, actor, and timestamp
were recorded for this stage attempt?" Decision history is business state, not
an Envers reconstruction and not a workspace artifact. If a gate is decided
again because the guarded stage artifact was reworked, the prior decision
remains queryable; the current gate state may reopen, but prior decision history
is not erased.

Research review comments are a third concept: run/gate-scoped discussion and
resolution state around a decision or artifact. A comment can inform a later
decision, but it is not itself the gate decision.

This is not a generic approval engine or generic comment platform. It is part of
the `ResearchRun` Service+Aggregate boundary and exists because research gate
history, reviewability, and accountability have product semantics beyond the
current stage blocker.

### 2. Recommendation provenance and decision provenance are distinct

A gate decision-log entry carries bounded lifecycle metadata:

- owning `ResearchRun`;
- `ResearchGatePoint`;
- guarded `ResearchRunStage`;
- stage attempt or artifact/checkpoint reference when available;
- bounded question text or question key;
- bounded recommendation option identifier and recommendation summary;
- bounded rationale summary;
- recommendation provenance, including whether the recommendation was authored
  by an agent, a system policy, or a human reviewer;
- decision outcome;
- decision actor and timestamp;
- policy basis and optional run-scoped idempotency/source-action identity.

The decision actor for a human or service approval comes from the authenticated
server context (`ActorFilter` / `ActorHolder`), not from the request body. Agent
or system recommendation provenance may be accepted as bounded metadata, but it
does not authenticate or authorize the caller and must not be used as the
decision actor. Autonomous decisions remain first-class decision-log entries:
they use explicit autonomous/policy provenance rather than pretending a human
approved them.

Any actor/provenance type exposed through REST, frontend, or MCP is a closed
API-visible vocabulary and follows ADR-034 mirror/drift rules.

### 3. Review comments are run-scoped product data

Model research review comments under the research domain as run-scoped product
data. They are sibling state to `ResearchRunGate` and `ResearchRunArtifact`, not
fields on `Project`, not requirement `QualityGate` rows, not workflow telemetry,
and not GitHub issue or pull-request review comments.

Every review-comment record must resolve through a single `ResearchRun` and its
project. Cross-project access is concealed the same way as existing research-run
reads and writes.

### 4. A review target is explicit and closed

A comment must name a bounded review target instead of embedding arbitrary file
paths or prose locations. The initial target vocabulary is:

- the whole run;
- a gate point;
- a lifecycle stage;
- a run artifact manifest row;
- a gate decision-log entry.

The target is metadata used for filtering, snapshot display, and audit. It is
not a content locator. If later work needs paragraph-level manuscript
annotations or source-row annotations, add a document/source-ledger reference at
that domain boundary rather than storing raw offsets into workspace-local files.

### 5. Comment text is bounded review metadata, not artifact content

Review comments and resolution summaries carry bounded human-readable metadata:
the question or concern, a compact recommendation or requested change, and the
resolution summary. They must not become storage for research artifacts.

The backend enforces length limits at DTO and service layers. API, MCP, logs,
telemetry, and errors keep the same no-raw-content rule as ADR-064 and ADR-065.

### 6. Authenticated actor and content provenance are separate

The authenticated decision actor, comment author, and comment resolver are always
derived from `ActorFilter` / `ActorHolder`. Request bodies and MCP payloads must
not accept `author`, `resolvedBy`, `actor`, or equivalent identity fields.

Review comments may carry a small provenance label, such as human review, agent
recommendation, or system check, to support explainability and later filtering.
That label is descriptive data only. It does not authenticate the caller, grant
permissions, or override the actor recorded from the security context.

### 7. Comment resolution is independent from gate decisions

Resolving a review comment means the review concern was addressed or explicitly
closed. It is not the same event as approving, rejecting, or auto-accepting a
gate.

Stage legality remains centralized in `ResearchRunService`. Unresolved comments
must not block advancement through frontend conditionals, MCP handlers, or
controller checks. If a review comment is intended to block advancement, that
must be an explicit domain-service rule over a bounded flag or policy and must
be evaluated in the same transition path that already enforces artifacts and
gates.

### 8. Reads compose with the existing research surfaces

Resolved decisions are queryable by project-scoped run and by stage/gate point.
Run, gate, artifact, and snapshot reads may include bounded decision/comment
summaries or counts. Detailed decision and comment history belongs behind
explicit run-scoped reads with project-scoped filtering by target, status, and
provenance.

Read contracts use `stage` / `currentStage`, not skill phase numbers or
`workflow_phase_event.phase` strings. Do not teach the observability snapshot to
parse `decisions.md`, GitHub issue threads, PR review comments, local
transcripts, or workspace files to discover review state.

### 9. Cross-cutting layers stay shared

- **Security and authorization:** use ADR-026 bearer/session chains and
  `ApiPathMatrix`; project scope is resolved through `ProjectService`; no
  feature-local auth, actor override header, or caller-supplied actor field.
- **Validation:** REST DTOs use Bean Validation and Jackson enum parsing; MCP
  mirrors use Zod and ADR-034 drift checks for API-visible enums. The service
  owns run/project consistency, target consistency, decision/gate/stage/attempt
  consistency, comment/resolution state transitions, bounded text validation,
  and idempotency/conflict behavior.
- **Errors:** use `DomainValidationException`, `ConflictException`, and
  `NotFoundException` through `GlobalExceptionHandler` and `ErrorResponse`. Do
  not add a research-specific error envelope.
- **Audit and actor provenance:** mutations use `ActorFilter` / `ActorHolder`
  and Envers-style audited aggregate patterns. Envers is history; it is not the
  decision-log or comment-resolution state model.
- **Logging:** use SLF4J with low-cardinality fields such as project, run ID,
  target type, target ID, gate point, stage, decision outcome, comment status,
  and provenance class. Do not log comment bodies, raw recommendation text, raw
  resolution text, prompts, manuscripts, source rows, provider payloads, tokens,
  secrets, or private absolute paths.
- **Configuration and OS/runtime exposure:** this feature introduces no new
  secrets, subprocesses, shell-outs, provider calls, GitHub writes, or
  token-in-argv path. Future orchestration or external review adapters belong at
  the ADR-028 boundary.
- **Testing:** controller paths need `@WebMvcTest` slices for validation and
  envelope coverage; service tests own status transitions, project scoping,
  target consistency, provenance separation, and any blocking-comment policy.
  Repo completion still runs `make policy`.

## Consequences

### Positive

- Stage gating, recommendation history, human approval, autonomous acceptance,
  and review comments are inspectable without reading workspace files or
  replaying audit tables.
- Human accountability is explicit: a human decision actor is not confused with
  the agent or policy that produced the recommendation.
- Rework can reopen the current gate while preserving the earlier decision that
  applied to the superseded artifact attempt.
- Review discussion around gates and artifacts stays product-visible without
  making GitHub issue comments or workspace files part of the research-run
  state model.

### Negative

- Issue #1001 needs more than a field extension on the current gate row if it
  must preserve decision history and review comments across rework.
- The research domain gains another durable surface. It must stay bounded to
  decision/review metadata and avoid becoming a document annotation engine.
- API/MCP surfaces that expose new provenance or target vocabularies must
  participate in the same enum and DTO mirror discipline as the existing
  research-run surface.

### Risks

- If implementation treats the current gate row as the whole log, rejected or
  superseded decisions can disappear from product reads.
- If actor identity is accepted from the request body, human approval or comment
  resolution can be spoofed despite ADR-026 and ADR-033.
- If decision/comment bodies are treated as artifact storage, unpublished
  research content can leak through broad reads, logs, errors, telemetry, or MCP
  payloads.
- If GitHub or workspace comments are treated as authoritative, Ground Control
  can disagree with its own lifecycle and audit state.

## Non-Goals

- No implementation of entities, migrations, controllers, MCP tools, frontend
  views, or graph projection in this ADR.
- No generic approval engine or generic comment/annotation platform.
- No replacement of `ResearchRunGate`, `ResearchRunArtifact` checkpoint
  manifests, ADR-064 lifecycle rules, or ADR-065 snapshots.
- No new authentication model, authorization policy, error envelope, logging
  stack, workflow engine, external review adapter, or document storage decision.
- No full-text, PDF, manuscript, charting-row, prompt, or search-result storage
  decision.

## Related Requirements

- `GC-RSCH-R003` - autonomous/copilot modes and human gates.
- `GC-RSCH-F004` - provide human gates with recommendation and rationale.
- `GC-RSCH-F034` - support human review comments and resolution tracking.
- `GC-RSCH-N012` - explainability.
- `GC-RSCH-N013` - human accountability.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-028 - Temporal Workflow Orchestration Boundary.
- ADR-029 - Issue-Thread Gate Model.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-065 - Research Run Observability Snapshot.
