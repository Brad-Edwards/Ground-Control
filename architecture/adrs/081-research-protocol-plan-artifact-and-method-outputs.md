# ADR-081: Research Protocol Plan Artifact and Method-Specific Outputs

## Status

Accepted

## Date

2026-07-02

## Context

`GC-RSCH-F009` requires Ground Control research runs to support
method-specific outputs for scoping reviews, systematic reviews, systematic
maps, critical/integrative reviews, targeted related work, and taxonomy
development. Issue #1007 also requires a protocol planning artifact that traces
every methodology requirement to a filled answer, user gate, or explicit
deferral, and refuses search execution while blocking gates remain unresolved.
`GC-RSCH-N016` also requires outputs to expose negative results, failed
searches, access gaps, missing evidence, method limits, and non-claims instead
of presenting only successful or affirmative evidence.

Ground Control already has the adjacent owners:

- ADR-055 owns the skill-side literature-review workflow and citation MCP.
- ADR-064 owns `ResearchRun`, lifecycle stages, gate points, artifact manifests,
  checkpoint/resume, and the rule that persisted run state is authoritative over
  workspace file existence.
- ADR-066 owns durable gate-decision logging and run-scoped review comments.
- ADR-067 owns the rationale ledger.
- ADR-069 owns bounded provenance nodes and edges.
- ADR-073 owns the research extensibility and adapter boundary.
- ADR-075 and ADR-076 own factuality, method limits, non-claims, and humility
  exposure.
- ADR-077 owns behavior version coordinates and regression expectations.
- ADR-078 owns the backend methodology catalog and required-source snapshots.
- ADR-080 owns the structured phase-1 methodology requirements contract that
  protocol planning consumes.

The remaining gap is the phase-2 protocol plan itself. Without a decision,
likely failure modes are: storing `lit-review-plan.md` prose directly on
`ResearchRunArtifact`; parsing workspace markdown as the source-search gate;
creating one schema/controller/table per methodology; treating taxonomy source
roles as a generic search corpus; duplicating rationale/provenance/gate
concepts inside the plan body; hiding method limits, non-claims, missing
evidence, access gaps, failed searches, or negative results in prose; letting
MCP handlers or skills decide whether search may start; or accepting a plan
that leaves blocking protocol questions unresolved.

## Decision

### 1. Phase 2 produces a structured protocol-plan contract

The protocol plan is the structured content behind the
`ResearchArtifactType.PROTOCOL_PLAN` lifecycle manifest. The artifact manifest
continues to prove that the `PROTOCOL_PLANNING` stage produced an output; it is
not the artifact body and does not become a free-form markdown store.

The protocol plan is scoped to one `ResearchRun`, one active
`METHODOLOGY_REQUIREMENTS` contract attempt from ADR-080, and one
`PROTOCOL_PLAN` artifact attempt. A rework records a new artifact attempt and a
new protocol-plan contract rather than mutating the prior accepted plan.

The plan stores bounded fields, safe references, and short summaries only. It
does not store raw workspace files, full plans, queries with secret-bearing
parameters, full source text, PDFs, charting rows, prompts, completions,
provider payloads, manuscripts, credentials, or private absolute paths.

### 2. Coverage of the methodology contract is explicit and complete

Every current ADR-080 `REQUIREMENT` and `OPEN_PROTOCOL_QUESTION` entry must have
exactly one plan coverage disposition keyed by the stable contract `entryKey`.
`METHOD_LIMIT` and `NON_CLAIM` entries remain constraints that the plan carries
forward and later stages must respect.

The initial coverage dispositions are:

- `FILLED` - the plan provides a bounded answer with source/provenance
  classification.
- `RESOLVED_BY_USER_DECISION` - the answer depends on a durable user decision
  recorded through existing gate-decision/rationale surfaces.
- `DEFERRED_NON_BLOCKING` - the plan explicitly defers the answer to a later
  stage, with rationale and the later stage or trigger that will resolve it.
- `NOT_APPLICABLE_WITH_RATIONALE` - the selected method/profile does not require
  the entry for this run, with bounded rationale.
- `BLOCKING_DECISION_REQUIRED` - the plan cannot be accepted as an active
  protocol until a decision is made.

The service must reject an active `PROTOCOL_PLAN` when any contract entry is
missing coverage or has `BLOCKING_DECISION_REQUIRED`. Search execution is
therefore blocked by the existing stage prerequisite: `SOURCE_SEARCH` requires
an active `PROTOCOL_PLAN`. Source-search REST/MCP entry points must also recheck
that the active protocol plan is complete so callers cannot bypass the lifecycle
frontier by invoking a lower-level action directly.

This does not create a new global `ResearchGatePoint`. The existing
`PROTOCOL_DECISION` still guards approval to leave the `PROTOCOL_PLANNING`
stage. Protocol-entry decisions are plan coverage facts that may reference
existing decision-log and rationale records; they are not another gate taxonomy
competing with ADR-064/ADR-066.

### 3. Method-specific shape is profile-driven, not one aggregate per method

The selected method profile determines which protocol sections, source roles,
output obligations, and claim limits apply. The plan records the method key,
method profile version, methodology contract id/attempt, and protocol schema
version that shaped acceptance.

The extension seam is a versioned protocol-output shape associated with a method
profile, using closed vocabularies such as section kind, source role, answer
kind, evidence role, and output obligation. Adding a future method or variation
should be a catalog/profile/schema addition plus validator coverage where
possible, not a new controller, MCP tool, repository, or table by default.

The initial method families must remain distinct:

- Scoping reviews need PCC/scope framing, information sources, search strategy,
  screening, charting, synthesis/reporting, consultation posture, critical
  appraisal decision, protocol registration, method limits, and non-claims.
- Systematic reviews need eligibility, databases/search strings, screening,
  extraction, risk-of-bias/quality posture, synthesis plan, reporting standard,
  certainty/claim limits, and non-claims.
- Systematic maps need mapping questions, search and screening plan, coding/map
  schema, classification provenance, visualization/output obligations, and
  limits on causal or quality-weighted claims.
- Critical/integrative reviews need theoretical frame, selection rationale,
  appraisal/critique dimensions, synthesis argument posture, inclusion limits,
  and explicit non-claims.
- Targeted related work needs bounded purpose, seed/source strategy, inclusion
  rationale, comparison dimensions, non-exhaustiveness disclosure, and limits on
  prevalence or systematic-coverage claims.
- Taxonomy development needs meta-characteristic, unit of analysis, separate
  source roles, starting concepts with provenance, construction procedure,
  iteration log protocol, ending conditions, evaluation plan, validity threats,
  and explicit non-claims.

Taxonomy development is the hard boundary case. Taxonomy-instance corpus,
background/framing literature, methodology literature, and validation/evaluation
material must remain separate source roles. Background/framing sources do not
support recurrence, prevalence, coverage, exhaustiveness, or taxonomy validity
claims unless the protocol output shape explicitly assigns that evidentiary
role and the service accepts that assignment.

### 4. Answer provenance is typed and bounded

Each filled answer must classify where it came from:

- methodology source or contract entry;
- paper/research intake context;
- durable user decision;
- citable source resolved through the citation/Zotero boundary;
- pilot/emergent decision deferred to a later stage;
- adapter/tool output accepted through a structured service command.

This classification is not proof by itself. It tells later validation which
existing surface must support the answer: methodology source coverage, intake,
gate-decision log, rationale ledger, provenance graph, source identity, or a
later artifact attempt. The plan must not treat model memory, skill prose, or
workspace-local file text as accepted evidence.

### 5. Existing research records keep their jobs

The protocol plan composes existing research concepts instead of replacing
them:

- `ResearchRunArtifact` remains the lifecycle manifest and resume frontier.
- The ADR-080 methodology requirements contract remains the source of
  requirements, method limits, non-claims, and open protocol questions.
- `ResearchRunGate` and `ResearchRunGateDecisionLog` remain lifecycle gate and
  decision-log surfaces.
- `ResearchRunRationaleEntry` remains the "why" ledger for non-obvious choices,
  deferrals, deviations, and method/output limits.
- `ResearchProvenanceNode` and `ResearchProvenanceEdge` remain the "from what"
  derivation ledger.
- The methodology catalog remains the selected method/profile source of truth.
- Factuality and humility checks remain service-composed validations over
  accepted research state, not prose parsers.

Do not convert protocol entries into Ground Control `Requirement` rows, GRC
`EvidenceArtifact` rows, generic `Document` content, `TraceabilityLink` rows, or
workflow telemetry events.

### 6. Scientific humility is explicit at protocol time

The accepted protocol plan must expose the scientific-humility facts available
before search starts:

- phase-1 `METHOD_LIMIT` entries carried forward as limits on later search,
  synthesis, argument, drafting, and final-output claims;
- phase-1 `NON_CLAIM` entries carried forward as explicit boundaries;
- `DEFERRED_NON_BLOCKING`, `NOT_APPLICABLE_WITH_RATIONALE`, and resolved user
  decisions with bounded rationale rather than omitted answers;
- known missing evidence classes, source roles, or validation needs that the
  protocol intentionally leaves to a later stage or pilot;
- known pre-search access/source gaps, when already accepted by the relevant
  source-identity surface, as access-gap facts rather than filled evidence.

The protocol plan must not claim that later-stage N016 facts are absent merely
because search has not run. Failed searches, negative results, access gaps found
during acquisition, charting conflicts, and synthesis-level missing evidence
remain owned by the later source, observability, factuality, provenance, and
humility surfaces from ADR-065, ADR-071, ADR-075, and ADR-076. The protocol may
declare where those facts will be reported, but it does not resolve them.

No humility category is an exception envelope, log line, or workflow telemetry
event. It is bounded product metadata linked to the run, artifact attempt,
methodology contract entry, disposition, rationale/provenance record where
applicable, and timestamp.

### 7. Cross-cutting contracts remain shared

- **Security and authorization:** routes stay under `/api/v1/research-runs/**`
  and inherit ADR-026 through `ApiPathMatrix`. Every read/write resolves exactly
  one project and run; cross-project or cross-run references are concealed as
  `404`.
- **Validation:** REST DTOs use Bean Validation and Jackson enum binding; MCP
  mirrors use flat Zod schemas and body-field allowlists. Services own
  same-run checks, active artifact-attempt checks, complete contract coverage,
  unresolved-blocker rejection, method-profile/output-shape compatibility,
  source-role legality, bounded text, idempotency, and current-vs-superseded
  filtering.
- **Persistence:** use the audited aggregate pattern under `domain/research`:
  `BaseEntity`, Flyway migration plus audit shadow table, project/run-scoped
  repository methods, foreign keys to owning run/artifact/contract records, and
  database-backed uniqueness/idempotency constraints where feasible.
- **Errors:** use `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`. Error details use stable codes
  and bounded field names; they do not echo plan prose, query strings, source
  text, provider payloads, stack traces, tokens, or local paths.
- **Actor provenance and audit:** mutation actors come from `ActorFilter` /
  `ActorHolder` and Envers revision metadata. Request DTOs and MCP schemas must
  not accept caller-supplied audit actors.
- **Logging:** log low-cardinality fields only: project, run id, artifact id,
  attempt, method key/version, protocol schema version, section kind,
  disposition, source-role counts, decision/rationale ids, idempotency
  reference, and stable error code. Do not log plan prose, query bodies, source
  excerpts, prompts, manuscripts, provider payloads, bearer tokens, Zotero
  secrets, Git credentials, or private absolute paths.
- **Configuration and OS/runtime exposure:** the protocol-plan artifact
  introduces no new secrets, subprocesses, shell-outs, provider calls, citation
  calls, arbitrary filesystem scans, GitHub writes, or token-in-argv path.
  Citation, Zotero, local/offline source, renderer, and provider effects remain
  behind ADR-055/ADR-071/ADR-073 adapter boundaries and re-enter through
  structured service commands.
- **MCP:** curated writes belong on `gc_research_run` unless a later aggregate
  has a materially different contract. MCP handlers mirror REST; they do not
  parse workspace files, infer requirement coverage, call providers, or
  reimplement method-output validators.
- **Graph/provenance:** relational research records remain source of truth. AGE
  projection follows ADR-070 through `GraphProjectionContributor`; no direct
  graph writes and no graph source-of-truth for protocol coverage.
- **Testing and policy:** controller additions need `@WebMvcTest` slices;
  service tests cover complete coverage, blocker rejection, search bypass
  rejection, method-profile shape compatibility, taxonomy source-role legality,
  same-run/project isolation, supersession, idempotency, N016 humility
  carry-forward, and content-leak guards. Public enums, MCP fields, and OpenAPI
  mirrors follow ADR-034. Repo completion still runs `make policy`.

## Consequences

### Positive

- `GC-RSCH-F009` gets method-specific outputs without forking the research
  domain into one schema per method.
- Search execution has a durable backend gate: an active protocol plan with no
  unresolved blocking coverage, not a parsed workspace markdown file.
- Phase separation stays clear: methodology extraction defines obligations,
  protocol planning fills or defers them, and later stages execute against the
  accepted plan.
- Taxonomy-development source roles stay explicit, preventing background
  literature from silently becoming evidence for taxonomy claims.

### Negative

- The protocol plan needs a dedicated structured schema instead of only storing
  `lit-review-plan.md` as prose.
- Implementations must thread contract entry keys, artifact attempts, method
  profile versions, and protocol schema versions through the service boundary.
- Some method-specific validators will still require explicit code until enough
  common shape can be safely data-driven.

### Risks

- If coverage dispositions are optional or free text, the search gate becomes
  unenforceable.
- If method-specific output shape is stored as an untyped map, validation and
  UI/MCP clients will drift.
- If protocol-entry decisions are treated as new lifecycle gates, the repo will
  have two competing gate concepts.
- If taxonomy source roles collapse into a generic corpus, later taxonomy,
  recurrence, coverage, and validity claims will be over-supported.
- If raw plans, queries, prompts, source excerpts, or local paths leak through
  errors, logs, MCP responses, graph properties, or audit rows, unpublished
  research material can escape the intended boundary.

## Non-Goals

- No implementation of entities, migrations, repositories, controllers, DTOs,
  MCP tools, frontend views, graph contributors, parsers, validators, or method
  schemas in this ADR.
- No raw plan/document store, PDF/full-text store, prompt/completion store,
  query-result store, manuscript store, or generic charting-row store.
- No conversion of protocol entries into Ground Control requirements,
  traceability links, GRC evidence artifacts, workflow telemetry, or document
  sections.
- No new methodology engine, workflow engine, approval engine, policy runner,
  dynamic plugin execution, source provider, citation adapter, or graph
  source-of-truth.
- No new authentication model, actor override mechanism, exception hierarchy,
  error envelope, logging stack, enum-mirror system, GitHub side-effect path, or
  token-in-argv path.

## Related Requirements

- `GC-RSCH-F008` - executable protocol traces requirements to filled answers,
  gates, or deferrals.
- `GC-RSCH-F009` - method-specific outputs for review and taxonomy methods.
- `GC-RSCH-R001` - distinct research lifecycle stages.
- `GC-RSCH-R002` - never treat model memory as scientific evidence.
- `GC-RSCH-N012` - explainability.
- `GC-RSCH-N016` - scientific humility.

## Related Issues

- #1007 - Research protocol planning artifact.
- #1032 - Migrate Reactor discipline and artifacts into Ground Control research
  workflows.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-066 - Research Gate Decision Log and Review Comments.
- ADR-067 - Research Explainability Rationale Ledger.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-070 - Research Artifact Graph Projection.
- ADR-071 - Research Interoperability and Source Identity Boundary.
- ADR-072 - Research REST and MCP Tool Surface.
- ADR-073 - Research Extensibility and Adapter Boundary.
- ADR-075 - Research Factuality and Claim Grounding Boundary.
- ADR-076 - Research Scientific Humility Surface.
- ADR-077 - Research Behavior Versioning and Regression Tests.
- ADR-078 - Research Methodology Catalog as Backend Reference Data.
- ADR-080 - Research Methodology Requirements Contract Artifact.
