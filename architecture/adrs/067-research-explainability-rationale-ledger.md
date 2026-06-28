# ADR-067: Research Explainability Rationale Ledger

## Status

Accepted

## Date

2026-06-28

## Context

`GC-RSCH-N012` requires research work to expose the rationale behind
methodology choice, search decisions, exclusions, charted values, synthesis
claims, and writing claims. Existing research ADRs already cover adjacent
surfaces:

- ADR-055 owns the skill-side literature-review workflow, citation MCP, two-state
  source rule, workspace artifacts, `decisions.md`, evidence matrix, Argdown
  map, and manuscript-grounding disciplines.
- ADR-064 owns durable `ResearchRun` lifecycle state, artifact manifests,
  run-scoped gates, and gate decisions. It explicitly says `decisions.md` is not
  the lifecycle authority.
- ADR-065 owns the bounded run observability snapshot and forbids reading
  workspace files, transcripts, or raw artifacts to render status.
- ADR-066 owns run-scoped gate decision logs and research review comments.
  Comments are review discussion, not the authoritative explanation for every
  source exclusion, charted value, synthesis claim, or writing claim.

Without a focused explainability decision, likely failure modes are:

- bloating `ResearchRunGate` with every rationale shape, making human approval
  decisions double as source-exclusion, charting, synthesis, and writing-claim
  schemas;
- stuffing raw charting rows, manuscript prose, search-result payloads, PDF
  text, or private workspace paths into `ResearchRunArtifact` manifest rows;
- treating workspace `decisions.md`, `search-log.md`, `charting-data.csv`, or
  `argument-map.argdown` as the backend source of truth;
- inventing parallel validation, error envelopes, actor fields, logging,
  security, or MCP write semantics for research explainability;
- using free-text phase names, artifact kinds, source dispositions, or claim
  categories that drift from the closed research lifecycle vocabulary.

The design need is narrower than a document store: Ground Control needs a
durable, queryable explanation surface that can answer "why did this research
run choose or claim this?" without storing the full research artifact content.

## Decision

### 1. Explainability is a run-scoped rationale ledger

Add research explainability as a sibling aggregate under `domain/research`, not
as fields on `ResearchRun`, `ResearchRunGate`, `ResearchRunArtifact`,
`WorkflowRun`, `EvidenceArtifact`, `Document`, or requirements `QualityGate`.

The aggregate is a run-scoped ledger entry. Each entry records a bounded
rationale for a specific explainable research object:

- methodology choice;
- protocol or search decision;
- source exclusion or access-gap decision;
- charted value;
- synthesis claim;
- writing claim.

The ledger belongs to the `ResearchRun` execution boundary. It is project-scoped
through the owning run and follows the existing audited aggregate pattern:
`BaseEntity`, `@Audited`, project-scoped repository queries through the run,
Flyway migration plus audit shadow table, service-owned writes, and read DTOs
mapped by controllers.

### 2. Reuse lifecycle identity; do not invent phase strings

Each ledger entry must reference the existing lifecycle vocabulary:

- owning `ResearchRun`;
- `ResearchRunStage`;
- optional `ResearchArtifactType`;
- optional `ResearchRunArtifact` id or attempt number when the rationale is tied
  to a specific artifact version;
- optional `ResearchGatePoint` when the rationale explains or was settled by a
  gate decision.

Do not use skill phase numbers, `workflow_phase_event.phase`, free-text stage
names, or workspace filenames as the semantic stage authority. Workspace
locators may be stored only as bounded references already allowed by ADR-064,
not as proof of lifecycle state.

### 3. Use a closed explainability vocabulary with bounded references

The ledger must use closed enums for dimensions callers filter on:

- entry kind: methodology choice, search decision, exclusion, charted value,
  synthesis claim, writing claim;
- evidence basis: methodology source, user decision, cited source, full-text
  span, charted cell, evidence-matrix cell, argument-map premise, manuscript
  citation, policy/default, or explicit limitation;
- provenance: human, agent recommendation, autonomous default, imported
  artifact, or adapter.

Entry payloads are bounded metadata only:

- stable subject key or external reference, such as method key, query id,
  source id, charting field, claim id, paragraph id, or citation id;
- bounded rationale summary;
- optional bounded evidence locator, hash, or reference into the workspace
  artifact;
- optional confidence or limitation summary;
- actor/provenance and timestamps from server-side context.

Do not store raw prompts, completions, full search-result bodies, source PDFs,
abstracts as charting substitutes, charting-row payloads, manuscript prose,
private absolute paths, bearer tokens, Zotero secrets, provider headers, or
long provider error messages.

### 4. Gate decisions remain gate decisions

`ResearchRunGate` remains the durable decision record for the five gate points
defined in ADR-064. Gate rows may carry a bounded `rationaleSummary`, selected
option id, outcome, policy basis, and actor.

The explainability ledger does not replace gates, and gates do not replace the
ledger. When a gate rationale is also relevant to NFR-12, implementation may
create a ledger entry linked to the gate point or gate row, but lifecycle
advancement still reads gate state from `ResearchRunGate`, not from the ledger.

### 5. Artifact manifests remain manifests

`ResearchRunArtifact` remains the checkpoint authority for whether a stage
produced an active artifact. A ledger entry may point at an artifact manifest
row and a bounded subject key inside that artifact, but it must not duplicate
the artifact content.

Rework follows ADR-064 supersession: if an artifact is superseded, new rationale
entries are tied to the new artifact or attempt. Prior rationale entries remain
historical and must not be mutated in place to describe the replacement.

### 6. Reads are project-scoped and bounded

REST reads should live under the existing research-run route family, for example
`/api/v1/research-runs/{id}/...`, and resolve the project exactly as other
research-run reads do. Cross-project explainability reporting, if ever needed,
is a separate admin-only surface.

Default reads should be paged or otherwise bounded, filterable by stage, kind,
artifact type, gate point, and subject key. Broad list responses must carry
summaries and safe references only. Raw artifact drill-down, full document
storage, or source-level inspection is out of scope for this ADR.

### 7. Cross-cutting layers stay shared

- **Security and authorization:** reuse ADR-026 bearer/browser chains and
  `ApiPathMatrix`. Resolve one project through `ProjectService`; conceal
  cross-project run misses as 404.
- **Validation:** REST DTOs use Bean Validation and Jackson enum parsing.
  Services own same-project lookup, run state checks, stage/artifact/gate
  consistency, length bounds, immutable historical-entry behavior, and any
  source-disposition or claim-reference invariants.
- **Errors:** use existing `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`. Do not add a research-specific
  error envelope or leak raw provider/artifact content in messages.
- **Actor provenance and audit:** mutation actors come from
  `ActorFilter` / `ActorHolder`. Clients do not supply audit actors.
- **Logging:** use SLF4J with low-cardinality fields: project, run id, stage,
  kind, artifact type, gate point, subject key hash/reference, actor, and
  provenance. Never log raw rationales, prompts, manuscripts, PDFs, source rows,
  bearer tokens, Zotero secrets, provider payloads, or private absolute paths.
- **Configuration and OS/runtime exposure:** this feature introduces no secrets,
  subprocesses, shell-outs, network calls, or token-in-argv path. Citation,
  provider, or orchestration side effects stay at ADR-028/ADR-055 adapter
  boundaries and re-enter through structured service writes.
- **MCP:** reads may use `gc_query` once routes are allowlisted by the existing
  path rules. Any curated MCP writes mirror REST through flat Zod schemas,
  handler-side required-field checks, existing `request` helpers, and ADR-034
  drift gates for mirrored enums.
- **Testing:** controller additions need `@WebMvcTest` slices; service tests
  cover project-scope rejection, bounded summaries, immutable history on rework,
  gate/artifact reference validation, and content-leak guards. API-visible enum
  mirrors follow ADR-034. Repo work still completes through `make policy`.

### 8. Extensibility seam

The extension seam is the explainability entry kind plus evidence-basis
vocabulary, not a new table or controller per phase. Adding an obvious future
variation such as peer-review response claim or taxonomy iteration rationale
should be an enum addition plus validation/rendering support, unless it has an
independent lifecycle, retention, indexing, or access-control requirement.

## Consequences

### Positive

- NFR-12 gets one queryable rationale surface that spans choices, exclusions,
  charting, synthesis, and writing without weakening ADR-064 gates, ADR-065
  observability, or ADR-066 review comments.
- The design reuses project scoping, service-owned transactions, Envers audit,
  actor provenance, validation, error envelopes, logging, REST/MCP boundaries,
  and controller test conventions.
- Prior artifact attempts and their rationales remain historically explainable
  after rework.

### Negative

- A new durable ledger table is justified because the rationale set crosses
  gate, artifact, source, charting, synthesis, and writing concepts. This adds
  schema and DTO surface that implementation must keep bounded.
- The ledger records references and summaries, not the full evidence. Users who
  need the complete charting row or manuscript text must open the referenced
  workspace artifact or a future document store.

### Risks

- If subject keys are not stable within an artifact attempt, users will see
  rationale entries that cannot be reconciled to the artifact they explain.
- If implementations treat missing rationale as acceptable for NFR-12-covered
  objects, the ledger becomes decorative instead of enforcing explainability.
- If raw research content is copied into ledger summaries, this surface can leak
  unpublished manuscripts, search strategy, source text, or provider secrets
  through logs, errors, MCP reads, or broad API responses.
- If gate decisions and explainability entries become mutually authoritative for
  lifecycle advancement, rework and autonomous-mode behavior can diverge from
  ADR-064.

## Non-Goals

- No implementation of entities, migrations, controllers, DTOs, MCP tools,
  frontend views, or workspace parsers in this ADR.
- No document store, PDF store, manuscript store, citation database, source-level
  ledger, or raw charting-row persistence decision.
- No replacement of ADR-055 skills/citation MCP, ADR-064 lifecycle/gates,
  ADR-065 observability, or ADR-066 review comments.
- No generic approval engine, generic claim-management engine, or reuse of
  requirements `QualityGate` for research decisions.
- No new authentication model, actor override mechanism, error envelope, logging
  stack, enum-mirror system, policy runner, or workflow engine.

## Related Requirements

- `GC-RSCH-N012` - explainability.
- `GC-RSCH-R003` - autonomous/copilot modes and human gates.
- `GC-RSCH-F004` - human gates with recommendation and rationale.
- `GC-RSCH-F034` - human review comments and resolution tracking.
- `GC-RSCH-N013` - human accountability.

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
- ADR-066 - Research Gate Decision Log and Review Comments.
