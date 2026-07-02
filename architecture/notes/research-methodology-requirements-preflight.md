# Research methodology requirements preflight

Requirement: `GC-RSCH-F005` - select review methodology from a catalog and
explicitly justify rejected alternatives.

Issue context: #1005, with related source-catalog/source-coverage requirements
`GC-RSCH-F006` and `GC-RSCH-F007`.

Amendment context: #1006 / `GC-RSCH-F008` - produce an executable protocol that
traces every requirement to a filled answer, user gate, or explicit deferral.
Update: ADR-081 is now the authoritative #1007 decision for the structured
phase-2 protocol planning artifact. It supersedes the earlier "not a new
protocol-content table by default" posture below once backend validation of F008
protocol coverage is required.

R-2 context: #1006 / `GC-RSCH-R002` - never treat model memory as scientific
evidence. Methodology requirements, method limits, rejected alternatives, and
non-claims need source evidence, a future explicit experiment/computation
artifact category, or explicit unsupported/inferred/non-claim labeling. Model
memory, prompts, completions, skill transcripts, catalog summaries, provider
metadata, local filenames, UI text, and MCP display text are not evidence.

## Architectural read

No new cross-cutting runtime architecture is needed before implementation. The
work must be a bounded extension of the existing research run surfaces:

- ADR-055 owns the skill-side method-selection and source-reading discipline.
- ADR-064 owns `ResearchRun`, `METHODOLOGY_SELECTION`,
  `METHODOLOGY_REQUIREMENTS`, artifact manifests, stage legality, gates, and
  checkpoint/resume.
- ADR-065 owns run observability, including source/access summaries and
  actionable bounded errors.
- ADR-067 owns the rationale ledger for method choices, exclusions, charted
  values, synthesis claims, and writing claims.
- ADR-068 owns final-output disclosure; disclosure is not proof of phase-1
  source grounding.
- ADR-069 owns provenance nodes/edges, including methodology-source derivation
  tracing, but provenance is not the stage gate.
- ADR-071 owns provider-neutral source identity and keeps Zotero/provider/local
  files as adapters or references.
- ADR-072 owns the REST/MCP research surface and keeps adapter logic thin.
- ADR-073 owns research extension boundaries and says methods are versioned
  catalog/profile data, not executable plugin code.
- ADR-075 owns factuality and claim grounding, including the rule that provider
  metadata alone is not scientific support.
- ADR-076 owns scientific humility exposure for failed searches, access gaps,
  missing evidence, method limits, and non-claims.
- ADR-077 owns method profile/source-completeness versioning and regression-test
  expectations for issue #1005.
- ADR-078 owns the backend methodology catalog as validated-on-load reference
  data and the policy drift check against the skill-side mirror.
- ADR-080, if adopted from the current worktree draft, owns the typed
  methodology requirements contract artifact and must still compose the
  existing lifecycle, source-coverage, rationale, provenance, and humility
  surfaces rather than replace them.

The canonical implementation incumbents are `ResearchRunService`,
`MethodologyCatalog`, `ResearchRunMethodologySelection`,
`ResearchRunMethodologySource`, `ResearchRunArtifact`,
`ResearchProvenanceNode` / `ResearchProvenanceEdge`,
`ResearchRunRationaleEntry`, `ResearchRunDisclosure`, `ResearchRunController`,
`ResearchProvenanceController`, the `gc_research_run` / `gc_research_provenance`
MCP tools, and `mcp/ground-control/openapi-contract.test.js`.

## Guardrails

Methodology requirements are run-local protocol obligations, not Ground Control
`Requirement` rows. Do not create UIDs, requirement statuses, requirement
traceability, or requirement relations for extracted methodology obligations.

The selected method key/profile version belongs on run-scoped methodology state
so the required-source set is historical. Rejected alternatives belong in the
existing rationale ledger as `METHODOLOGY_CHOICE` entries with bounded
source- or policy-backed rationale. Later catalog edits must not make an
existing run falsely complete or falsely incomplete.

The backend completion gate for `METHODOLOGY_REQUIREMENTS` is source coverage,
not artifact content parsing. A phase-1 artifact can be recorded complete only
when the selected method profile/catalog version has all required primary
methodology sources recorded as read/complete for that run. Optional sources may
be visible but must not block unless the selected method policy marks them
mandatory.

`ResearchRunArtifact(METHODOLOGY_REQUIREMENTS)` is the lifecycle manifest for the
phase-1 artifact, not the full artifact body and not the evidence model. If the
implementation needs protocol planning to consume structured phase-1 content,
that contract must remain run-scoped and bounded: method key/profile/catalog
version, selected-source references, extracted method obligations, method
limits, non-claims, open gates/deferrals, stable locators/hashes, and references
to provenance/rationale rows. Do not store raw source text, prompt/completion
text, manuscript prose, charting rows, provider payloads, or private workspace
paths in the broad research-run API.

Every extracted methodology obligation or method-limit statement must carry a
closed evidence/label basis. For phase 1, the expected grounded basis is a
methodology source that belongs to the active selection and is compatible with
the `READ` source-coverage gate, with provenance/rationale records providing
the "from what" and "why." Unsupported, inferred, and non-claim labels are
explicit humility/limitation facts; they must not satisfy source coverage, must
not be rendered as sourced evidence, and must not fill domain-specific protocol
answers.

The distinction between phase 1 and phase 2 is semantic and must be preserved in
the data shape:

- phase 1 may record method key/version, source reference, source section or
  locator, requirement statement, limitation, and source-grounded rationale;
- phase 2 owns selected databases, query strings, domain definitions,
  inclusion/exclusion logic, charting fields, synthesis dimensions, source-set
  caps, and other paper/domain answers.

Provenance and source identity may reference the same methodology sources, but
they do not replace source coverage. Provenance answers "from what"; source
identity answers "which source"; source coverage answers "was this required
methodology source obtained and read for this run?"

Rationale and disclosure also remain distinct. Rationale explains why a method
choice, extracted obligation, inferred boundary, or limit is acceptable.
Disclosure exposes final-output accountability and uncertainty. Neither surface
is itself source evidence.

## F008 protocol traceability amendment

`GC-RSCH-F008` extends the same boundary into phase 2. The executable protocol is
the `PROTOCOL_PLAN` artifact produced by `PROTOCOL_PLANNING`; its durable
checkpoint is still a `ResearchRunArtifact` manifest row with bounded locator /
hash / idempotency metadata, not a new protocol-content table by default.

The protocol body must trace every phase-1 methodology obligation to exactly one
of three dispositions:

- filled answer - the protocol supplies the paper-specific answer and identifies
  whether it came from paper context, methodology source, user decision,
  citable source, or pilot/emergent procedure;
- user gate - the protocol cannot proceed without a user judgement, so the gate
  is raised as it arises and resolved through the run's existing gate-decision
  surface before the relevant transition proceeds;
- explicit deferral - the user or method deliberately leaves the answer to a
  named later stage, pilot, or write-up point, with reason and owner/stage
  recorded as bounded metadata.

Do not turn those dispositions into Ground Control `Requirement` rows,
`QualityGate` rows, `TraceabilityLink`s, workflow telemetry events, or generic
`Map<String,Object>` blobs. They are protocol-coverage semantics for one
research run. Repository-level traceability still links `GC-RSCH-F008` to ADRs,
docs, tests, API surfaces, skills, and PRs; it is not the internal
requirement-to-answer table for a paper's protocol.

The authoritative lifecycle gate remains service state:

- `ResearchRunService` accepts the `PROTOCOL_PLAN` artifact only after the
  method requirements artifact exists and stage/gate legality is satisfied.
- `ResearchGatePoint.PROTOCOL_DECISION` is the existing approval/checkpoint
  surface for protocol-level user judgement; `decisions.md` is only a local
  mirror/export.
- `ResearchRunRationaleEntry` and `ResearchProvenanceNode`/`Edge` can explain
  why a filled answer, gate, or deferral exists and what it derives from, but
  neither replaces the protocol artifact nor decides stage advancement.

If a later implementation needs machine validation of the protocol body, add an
explicit structured protocol-coverage parser/validator at the research service
boundary, keyed by selected `methodKey` / profile version and the active
artifact attempt. Do not hide markdown parsing in controllers, MCP handlers,
frontend code, `gc_query`, policy scripts, or skill prose. Any new public
disposition enum, rationale kind, provenance node kind, or MCP field follows
ADR-034/OpenAPI/MCP drift rules; do not overload existing names such as
`SEARCH_DECISION` to mean protocol coverage.

Protocol planning consumes the methodology requirements artifact as a contract
by stable keys/references, not by re-parsing Markdown, local files, source PDFs,
skill transcripts, provider payloads, or model output. Any inferred or
unsupported phase-1 item must remain visible to phase 2 as a gate, limitation,
non-claim, or explicit deferral; it must not silently become a filled answer.

The extensibility seam is the method-profile-aware protocol coverage shape:
future methods may require different requirement groups, source roles, pilot
rules, or deferral points, but they should extend the selected method profile /
validator vocabulary rather than adding per-method controllers, repositories,
MCP tools, or workflow engines.

## Cross-cutting concerns

REST writes stay under `/api/v1/research-runs/**`, resolve one project/run
through the existing services, and conceal cross-project misses as `404`.
Controllers bind DTOs and delegate; they do not decide source completeness.

Validation is layered: Bean Validation/Jackson at REST DTOs, Zod at MCP mirrors,
service-owned semantic checks for selected method version, source-reference
membership, required-source completeness, protocol-disposition compatibility
when a structured validator exists, same-run references, idempotency, and
bounded text. New API-visible enum values, DTO mirrors, action discriminators,
body-field allowlists, or `gc_query` read allowlists must follow ADR-034 and the
existing OpenAPI/MCP drift tests.

Errors use `GroundControlException` subclasses through
`GlobalExceptionHandler` and `ErrorResponse`. Source-completeness and
protocol-coverage failures should surface as stable, actionable run errors or
validation/conflict errors without raw provider payloads, PDF text, local paths,
stack traces, bearer tokens, Zotero secrets, or unpublished protocol content.

Actor provenance comes from `ActorFilter` / `ActorHolder` and Envers metadata.
Do not accept request-body actors for source attempts, reads, artifact
completion, or gate decisions.

Logging uses SLF4J with low-cardinality fields: project, run id, method
key/version, source reference id, required/optional status, source state, stage,
artifact type, protocol disposition, idempotency/source-action id, and stable
error code. Do not log source text, prompts, protocols, manuscripts, provider
payloads, secrets, or private paths.

This slice should introduce no subprocess, shell-out, provider call, GitHub
write, citation call, or token-in-argv path inside controllers, domain services,
MCP handlers, or frontend code. Provider/citation/Zotero/local-file effects stay
at existing adapter boundaries and re-enter as structured research service
commands.

Persistence changes, if any are justified beyond the existing manifest/source
rows, follow the existing audited aggregate pattern: `BaseEntity`, project/run
scope through `ResearchRun`, Flyway migration plus Envers audit shadow table,
database constraints for hard invariants, repository queries scoped by run, and
service-owned transactions. Do not add a second exception hierarchy, error
envelope, auth model, actor override path, logging stack, policy runner, or
workflow engine.

The extensibility seam is the method-requirement contract vocabulary:
obligation kind, evidence/label basis, source/reference locator, requirement
subject key, method limit/non-claim category, and future
experiment/computation artifact reference. Adding a review methodology, source
provider, extraction shape, or computation artifact should extend those
vocabularies and validators, not create a provider-specific phase-1 schema or a
generic `Map<String,Object>` escape hatch.

## Gotchas

Do not reuse the GRC `MethodologyProfile` aggregate for research methods; it is
risk-analysis vocabulary, not literature-review method policy.

Do not infer a source is read from Zotero membership, DOI resolution, a file
locator, a provenance node, a local `requirements.md`, or a skill transcript.

Do not infer a protocol requirement is answered from section headings, markdown
checkboxes, a local `lit-review-plan.md`, or a skill transcript unless a
service-owned structured validator has accepted that artifact attempt.

Do not treat a phase-2 explicit deferral as the repository workflow "defer"
disposition. Protocol deferrals are run-local research decisions with a named
later stage/pilot/write-up point; repo issue/PR findings still follow the
existing no-deferral policy.

Do not store catalog prose summaries that let a future agent substitute catalog
text for primary-source reading.

Do not treat model memory, an LLM extraction, a prompt/completion, a reviewer
comment, a skill transcript, a search result title, Zotero membership, DOI
resolution, or provider metadata as evidence for an extracted methodology
requirement.

Do not let an unsupported/inferred label bypass the required-source gate, and do
not let it flow into protocol planning as if the method source answered a domain
question.

Do not make MCP handlers, frontend conditionals, or controller branches a second
source-completeness validator. The research service owns the gate.

Do not broaden `gc_query` into a write tunnel or workspace-file reader. Curated
writes mirror REST; ad hoc reads stay GET-only and allowlisted.

Do not create a parallel claim/evidence/provenance/rationale database for
methodology requirements unless a later ADR proves an independent lifecycle,
indexing, retention, storage, or access-control need that the existing research
records cannot satisfy.

## Non-goals

No new generic workflow engine, methodology engine, source store, document
store, extraction validator, approval engine, dynamic plugin execution, error
envelope, auth model, logging stack, or policy runner.

The "No backend catalog loader" non-goal stated for this first slice is
superseded by ADR-078: the methodology catalog became backend-owned,
validated-on-load reference data (the single source of truth the selection gate
derives the required-source set from), with the skill file kept in sync by a
policy drift check. This is bounded reference data, not the generic methodology
engine the rest of this non-goal still rules out.

No backend guarantee, in this slice, that a free-form local `requirements.md`
contains no domain answers. The enforceable backend gate is required-source
coverage and artifact/stage legality. If machine validation of the artifact body
becomes required, add an explicit structured artifact parser/validator boundary
instead of hiding that logic in controllers, MCP tools, frontend code, or skill
prose.

No backend guarantee, in this slice, that a free-form local `lit-review-plan.md`
satisfies F008's requirement-to-answer/gate/deferral traceability. The skill
contract enforces that discipline today. If it becomes a backend invariant, add
the structured protocol-coverage validator described above.

No implementation of #1006 in this preflight note: no migrations, entities,
controllers, DTOs, MCP tools, frontend views, graph contributors, source
adapters, citation calls, artifact body parser, or protocol-planning consumer.
