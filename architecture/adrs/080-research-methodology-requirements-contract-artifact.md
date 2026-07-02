# ADR-080: Research Methodology Requirements Contract Artifact

## Status

Accepted

## Date

2026-07-01

## Context

Issue #1006 and `GC-RSCH-F007` require a durable methodology-requirements
artifact: phase 1 must extract formal requirements from primary methodology
sources without filling domain answers into that output. The issue also needs
the completed artifact to record the chosen method, rejected alternatives,
source-linked extracted requirements, method limits, non-claims, and open gates
that protocol planning consumes as its contract.

Ground Control already has several adjacent research surfaces:

- ADR-055 owns the skill-side phase-1 discipline and citation/source-reading
  workflow.
- ADR-064 owns `ResearchRun`, lifecycle stages, gates, artifact manifests, and
  the rule that `ResearchRunArtifact` is checkpoint metadata, not artifact
  content.
- ADR-067 owns the rationale ledger, including
  `RationaleEntryKind.METHODOLOGY_CHOICE`.
- ADR-069 owns the provenance ledger: "from what did this artifact/claim derive?"
- ADR-071 owns provider-neutral source identity boundaries.
- ADR-076 owns method limits, non-claims, missing evidence, and access-gap
  exposure.
- ADR-077 owns behavior-version coordinates and source-completeness regression
  expectations.
- ADR-078 owns the backend methodology catalog and run-scoped required-source
  snapshot.

The remaining gap is the structured phase-1 contract itself. Without a focused
decision, likely failure modes are: putting free-form `requirements.md` content
into `ResearchRunArtifact`; turning extracted methodology obligations into
Ground Control `Requirement` rows with synthetic UIDs; treating methodology
source coverage rows as extracted requirements; creating duplicate "why" or
"from what" schemas beside rationale/provenance; storing raw PDFs, prompts, or
methodology-source excerpts in API payloads; conflating open protocol-planning
questions with `ResearchGatePoint`; or accepting domain-filled answers in phase
1 because the data shape has fields for databases, query strings, date ranges,
charting categories, or source-set caps.

## Decision

### 1. Phase 1 produces a run-scoped methodology requirements contract

The phase-1 artifact is a structured research-domain contract for one
`ResearchRun`, not a Ground Control `Requirement`, not a `TraceabilityLink`, not
an `EvidenceArtifact`, and not a `ResearchRunArtifact` body field.

`ResearchRunArtifact` remains the lifecycle manifest that proves the
`METHODOLOGY_REQUIREMENTS` stage output exists and can unblock the next stage.
The methodology requirements contract is the bounded structured content behind
that manifest. The two must be tied by run, artifact type, and artifact attempt
so a downstream protocol plan can say exactly which contract attempt it filled.

The contract stores bounded metadata and safe references only. It does not store
raw methodology-source text, full PDFs, prompt/completion text, free-form
workspace file bodies, provider payloads, secrets, or private absolute paths.

### 2. Chosen method and rejected alternatives reuse existing decision surfaces

The chosen method is the active `ResearchRunMethodologySelection` snapshot from
ADR-078. The contract references that selection; it does not accept caller
supplied method labels, catalog versions, or required-source refs.

Rejected alternatives are methodology-choice rationale, not a new decision
schema. The contract may expose rejected alternatives as bounded method-key /
profile-version references linked to `ResearchRunRationaleEntry` rows of kind
`METHODOLOGY_CHOICE`, but the rationale ledger remains the authority for why an
alternative was rejected. If a rejected alternative is not in the backend
catalog, it must be marked as an external/manual method reference with bounded
label/provenance instead of being smuggled into the catalog.

### 3. Extracted entries use a closed methodology-contract vocabulary

The contract entries use a closed API-visible vocabulary. The initial semantic
classes are:

- `REQUIREMENT` - a methodology-source-derived obligation the protocol must
  satisfy.
- `METHOD_LIMIT` - a methodology-source-derived limit on what the selected
  method can claim.
- `NON_CLAIM` - an explicit boundary that phase 1 says the method or artifact
  does not assert.
- `OPEN_PROTOCOL_QUESTION` - a question or gate that phase 2 must answer,
  route to a user decision, or explicitly defer.

Every `REQUIREMENT`, `METHOD_LIMIT`, and `NON_CLAIM` entry must link to one or
more `ResearchRunMethodologySource` rows from the same active methodology
selection, with a bounded artifact-relative locator such as section, page, or
source-local anchor when available. `OPEN_PROTOCOL_QUESTION` entries must either
link to source rows directly or to the requirement/limit/non-claim entry that
creates the question.

Source links are references to accepted source-coverage state, not substitutes
for it. Required methodology sources still must be in `READ` state before the
contract is accepted. A source row from another run, a superseded selection, or a
different artifact attempt cannot support the current contract.

### 4. Phase-1 shape forbids domain-answer fields

The contract schema must make phase separation structural. Phase 1 may carry
methodology obligations and source-grounded locators, for example "the protocol
must state databases searched" or "the report must explain eligibility criteria."
It must not have first-class fields for the domain answers themselves:
databases, query strings, date ranges, inclusion/exclusion values, charting
categories, synthesis dimensions, source-set caps, target venues, result claims,
or paper-specific findings.

Those fields belong to the phase-2 protocol plan or later evidence artifacts.
Free-text statements remain bounded and source-linked, but backend acceptance of
the contract should not claim semantic proof that arbitrary prose contains no
domain answer unless a future ADR adds a structured parser/classifier boundary.
The v1 hard guard is the field shape plus service validation against forbidden
first-class answer fields.

### 5. Protocol planning consumes this contract, not workspace prose

The `PROTOCOL_PLAN` stage consumes the active methodology requirements contract
by contract id plus artifact attempt. The planning phase should fill, gate, or
defer each `REQUIREMENT` / `OPEN_PROTOCOL_QUESTION` by stable entry key, while
preserving `METHOD_LIMIT` and `NON_CLAIM` entries as constraints on later
search, synthesis, argument, and manuscript work.

This creates the seam for `GC-RSCH-F008`: coverage of phase-1 entries belongs to
the protocol-planning service boundary, not to parsing `requirements.md`, MCP
handler conditionals, frontend logic, or a skill transcript.

### 6. Cross-cutting contracts remain shared

- **Security and authorization:** routes stay under `/api/v1/research-runs/**`
  and inherit ADR-026 through `ApiPathMatrix`. Reads and writes resolve exactly
  one project through `ProjectService`; cross-project run, artifact, source, or
  rationale misses are concealed as `404`.
- **Validation:** REST DTOs use Bean Validation/Jackson; MCP mirrors use flat
  Zod schemas and ADR-034 drift checks. Services own same-run checks, active
  run/stage checks, active methodology selection checks, artifact-attempt
  consistency, required source coverage, entry/source-link completeness,
  rejected-alternative reference legality, idempotency, bounded text, and the
  phase-1 no-domain-answer field boundary.
- **Persistence:** use the existing audited aggregate pattern under
  `domain/research`: `BaseEntity`, `@Audited`, Flyway migration plus audit shadow
  table, project/run-scoped repository queries, foreign keys to the owning run,
  active methodology selection, artifact manifest, source rows, and rationale
  rows where applicable, plus database-backed uniqueness/idempotency constraints
  where feasible.
- **Errors:** use `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`. Validation/conflict details use
  stable codes and bounded field names; they do not echo source text, requirement
  prose, provider payloads, stack traces, tokens, or local paths.
- **Actor provenance and audit:** mutation actors come from `ActorFilter` /
  `ActorHolder` and Envers revision metadata. Request DTOs and MCP schemas must
  not accept caller-supplied audit actors.
- **Logging:** log low-cardinality fields only: project, run id, artifact id,
  attempt, method key/version, entry kind, source-link counts, rationale ids,
  idempotency key hash/reference, and stable error code. Do not log requirement
  statements, source excerpts, prompts, PDFs, manuscripts, provider payloads,
  bearer tokens, Zotero secrets, Git credentials, or private absolute paths.
- **Configuration and OS/runtime exposure:** this artifact introduces no new
  secrets, subprocesses, shell-outs, provider calls, citation calls, arbitrary
  filesystem scans, GitHub writes, or token-in-argv path. Citation/Zotero/local
  file effects remain at ADR-055/ADR-071/ADR-073 adapter boundaries and re-enter
  through structured service commands.
- **MCP:** curated writes, if exposed, belong on `gc_research_run` unless a new
  aggregate later has a materially different contract. Pure reads can use the
  existing `/api/v1/research-runs` `gc_query` allowlist. MCP handlers must not
  parse workspace files, infer source coverage, call providers, or validate
  phase-1 content independently of the backend service.
- **Graph/provenance:** relational research records remain source of truth. A
  provenance node may reference a contract entry when useful, but AGE projection
  must follow ADR-070 through `GraphProjectionContributor`; no direct graph
  writes and no new graph source-of-truth.
- **Testing and policy:** controller additions need `@WebMvcTest` slices;
  service tests cover project isolation, same-run source/rationale references,
  source-link completeness, method-selection locking, no-domain-answer field
  rejection, idempotency, artifact-attempt consistency, and content-leak guards.
  Public enums, MCP fields, and OpenAPI mirrors follow ADR-034. Repo completion
  still runs `make policy`.

### 7. Extensibility seam

The extension seam is the contract schema version plus entry-kind/source-link
vocabulary, not a new table or controller per methodology. The contract should
record a stable schema/version coordinate and the selected method profile
coordinate. Future method-specific variations belong in method profile policy,
entry-kind additions, source-link role additions, or extraction-schema
coordinates; they should not reopen caller-supplied required-source lists or
create a generic `ResearchMethodologyEngine.execute(Map)`.

## Consequences

### Positive

- `GC-RSCH-F007` gets a durable backend contract without weakening
  `ResearchRunArtifact`'s manifest boundary.
- Phase separation becomes structural: protocol answers have a downstream home
  and are not first-class fields in the phase-1 artifact.
- Chosen method, rejected alternatives, source coverage, rationale, provenance,
  humility, and lifecycle gates keep their existing owners.
- `GC-RSCH-F008` has a clear contract to consume: stable phase-1 entry keys tied
  to source-linked requirements and artifact attempt.

### Negative

- The artifact needs a dedicated structured schema instead of relying on a
  single markdown locator.
- Implementations must thread contract id/attempt and entry keys into protocol
  planning and later validation surfaces.
- The backend will not semantically prove arbitrary free text is domain-free
  unless a later parser/classifier boundary is deliberately added.

### Risks

- If entry keys are unstable across rework, protocol plans and provenance links
  will become hard to reconcile.
- If source links are optional, the artifact can regress into model-memory
  requirements with no scientific grounding.
- If method limits and non-claims are stored only in prose, later phases can
  overclaim while the API appears complete.
- If rejected alternatives are stored outside the rationale/provenance surface,
  method-choice explanations will drift.
- If raw source content or requirement prose is logged or projected broadly,
  unpublished research material can leak through APIs, MCP responses, graph
  properties, errors, logs, or audit tables.

## Non-Goals

- No implementation of entities, migrations, repositories, controllers, DTOs,
  MCP tools, frontend views, graph contributors, parsers, or validators in this
  ADR.
- No conversion of methodology obligations into Ground Control `Requirement`
  rows, requirement UIDs, requirement statuses, or requirement traceability
  links.
- No document store, PDF store, prompt/completion store, source-content store,
  manuscript store, or generic charting-row store.
- No new methodology engine, workflow engine, approval engine, policy runner,
  dynamic plugin execution, source provider, citation adapter, or graph
  source-of-truth.
- No new authentication model, actor override mechanism, exception hierarchy,
  error envelope, logging stack, enum-mirror system, GitHub side-effect path, or
  token-in-argv path.

## Related Requirements

- `GC-RSCH-F007` - extract formal requirements without domain answers.
- `GC-RSCH-F008` - protocol planning traces requirements to filled answers,
  gates, or deferrals.
- `GC-RSCH-R002` - never treat model memory as scientific evidence.
- `GC-RSCH-N012` - explainability.
- `GC-RSCH-N016` - scientific humility.

## Related Issues

- #1006 - Research methodology requirements artifact.
- #1005 - Research methodology catalog and primary-source tracking.
- #1032 - Migrate Reactor discipline and artifacts into Ground Control research
  workflows.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-067 - Research Explainability Rationale Ledger.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-071 - Research Interoperability and Source Identity Boundary.
- ADR-072 - Research REST and MCP Tool Surface.
- ADR-076 - Research Scientific Humility Surface.
- ADR-077 - Research Behavior Versioning and Regression Tests.
- ADR-078 - Research Methodology Catalog as Backend Reference Data.
