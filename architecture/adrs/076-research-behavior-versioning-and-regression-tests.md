# ADR-076: Research Behavior Versioning and Regression Tests

## Status

Accepted

## Date

2026-06-29

## Context

`GC-RSCH-N015` requires prompts, schemas, requirements, and workflow policies to
be versioned and regression tested because small changes can alter scientific
behavior. Issue #1005 applies that pressure first to methodology catalog entries
and primary-source coverage: a method profile can name required and optional
methodology sources, a run records source attempts and read/block state, and
methodology requirement extraction cannot complete when required sources are
missing.

Existing research ADRs already define the durable execution and adapter
boundaries:

- ADR-055 owns skill-side prompts, the methodology catalog, deterministic
  citation MCP use, and the two-state source rule.
- ADR-064 owns `ResearchRun`, stage legality, artifact manifests, gates,
  checkpoint/resume, and idempotency.
- ADR-069 owns the provenance ledger.
- ADR-071 owns provider-neutral source identity and source-disposition
  boundaries.
- ADR-072 owns the REST/MCP adapter surface.
- ADR-073 owns plugin-like extension identifiers, versions, capabilities, and
  typed contracts.

The remaining risk is maintainability drift. A small prompt edit, catalog entry,
schema field, source-state rule, or workflow-policy change can silently change
method choice, evidence inclusion, extracted requirements, charting behavior, or
drafting posture. Without a repo-level decision, likely failure modes are:

- treating skill prose as the only enforcement layer;
- storing raw prompt text, source content, provider payloads, or local paths as
  version evidence;
- creating duplicate schemas for the same method/source state across skills,
  REST, MCP, frontend, and policy tests;
- letting MCP handlers, controllers, or frontend code decide source completeness
  instead of research services;
- versioning catalog entries without recording which version a run used;
- snapshotting "latest" behavior only by mutable file path, making resume and
  audit nondeterministic;
- adding broad LLM-output golden tests that fail for wording variation while
  missing real contract regressions.

## Decision

### 1. Version behavior surfaces by stable coordinates, not mutable paths

Research behavior surfaces that can alter scientific outcomes carry a stable
coordinate when they become run-relevant:

- behavior family, such as method catalog, extraction schema, writing template,
  prompt/skill contract, source-completeness policy, or gate/workflow policy;
- stable key, such as method key, schema id, template id, skill name, or policy
  name;
- explicit version;
- optional content digest for file-backed artifacts;
- bounded provenance, such as adapter/tool name and version when the behavior
  was applied.

The coordinate is metadata. It is not a generic executor and not a license to
store raw prompts, full sources, manuscripts, charting rows, provider payloads,
secrets, or private absolute paths.

Implementations should attach these coordinates to the existing owning records:
run snapshots, artifact manifests, provenance nodes, rationale entries,
disclosures, method/source records, or extension metadata as appropriate. Do not
create a universal `ResearchBehaviorArtifact.execute(Map)` or generic behavior
engine.

### 2. Runs snapshot behavior that affects their decisions

When a run uses a method profile, source-completeness rule, extraction schema,
reviewer, writing template, or workflow/gate policy, the accepted service command
records the selected coordinate on durable run-scoped state. Later edits to the
catalog, prompt, schema, template, or policy do not rewrite active or completed
runs and do not silently change resume behavior.

For issue #1005, the methodology catalog entry is a versioned method profile
whose required/optional primary methodology source list feeds run-scoped source
coverage. Completing the methodology requirements artifact must be gated by
service-owned source-completeness evaluation against the snapshotted profile.
The artifact may reference the catalog/profile coordinate and source-coverage
records; it must not embed raw source content as the proof.

Methodology-source coverage is product state, not prose in `requirements.md` and
not a property of the provider record alone. It answers the run-scoped question:
for this selected method profile, which required or optional methodology sources
were attempted, obtained, read, or blocked? It may reference source identity
records, artifact manifests, provenance nodes, or bounded external identifiers,
but those referenced surfaces remain authoritative for their own jobs. A missing
or blocked required source prevents the active `METHODOLOGY_REQUIREMENTS`
artifact from being accepted until the coverage is reworked or the selected
method/source policy is explicitly changed through the research service.

### 3. Regression tests lock contracts, not prose wording

Regression tests for research behavior target deterministic contracts:

- catalog/profile parsing, required/optional source flags, and unknown-field
  rejection;
- source identifier normalization and source-disposition transitions from
  attempted to obtained, read, or blocked;
- source-completeness policy: required gaps block methodology requirement
  completion; optional gaps do not;
- stage/artifact legality through `ResearchRunService`, including idempotency
  and rework/supersession;
- REST DTO validation and `ErrorResponse` behavior through `@WebMvcTest`
  controller slices;
- MCP Zod shapes, action allowlists, snake_case-to-camelCase body mirrors, and
  request-helper routing;
- enum/body-field/OpenAPI/MCP drift surfaces covered by ADR-034 and existing
  policy checks;
- fixture-level skill or schema contract checks for mandatory sections, required
  source references, and absence of raw/provider/secret payload fields.

Do not use broad LLM prose snapshots as the primary regression signal. If a
prompt or writing template changes, tests should assert the enforceable
contract: required inputs, required output fields, source-state discipline,
blocklist or parser behavior, and bounded metadata.

### 4. One validation path owns each invariant

Research behavior versioning reuses the repo's existing enforcement layers:

- REST request shape: Bean Validation and Jackson enum binding.
- MCP request shape: Zod plus closed action/body-field allowlists.
- Semantic legality: research domain services, especially `ResearchRunService`
  for stage advancement, artifact completion, gates, and same-run references.
- Source-completeness legality: the research service evaluates the selected
  method profile snapshot and accepted source-coverage facts; controllers, MCP
  handlers, frontend code, skill prose, Zotero metadata, provenance edges, and
  workspace files do not decide that methodology requirements are complete.
- Persistence invariants: project-scoped repositories, foreign keys, uniqueness
  constraints, audit shadow tables, and idempotency keys.
- Errors: `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`.
- Audit actors: `ActorFilter` / `ActorHolder` and Envers, never caller-supplied
  actor fields.
- Logging: low-cardinality SLF4J fields with ids, versions, counts, statuses,
  and stable error codes only.
- Policy: `make policy` remains the repo-native guardrail for ADR, workflow,
  controller, MCP, and drift checks.

Do not add parallel validators in controllers, MCP handlers, frontend
conditionals, skill prose, or ad hoc scripts when one of these layers already
owns the invariant.

### 5. Sensitive research material stays out of version evidence

Version/provenance evidence may include stable ids, versions, digests, source
identifiers, bounded locators, counts, and short summaries. It must not include
raw prompts, model completions, manuscripts, full text, PDFs, charting rows, raw
search results, raw provider payloads, bearer tokens, Zotero secrets, Git
credentials, credential-bearing remotes, or private absolute paths.

Provider, Zotero, filesystem, Git, parser, and renderer options use validated
configuration or existing MCP-server environment boundaries. No new
token-in-argv, shell-out, workspace scan, or GitHub write path is introduced by
behavior versioning.

### 6. Extensibility seam

The seam is the stable behavior coordinate plus family specific parameters. A
new methodology, source role, schema variant, reviewer, or template should be a
data/contract addition in the relevant family, not a new controller, MCP tool,
repository, or aggregate by default.

A first-class behavior registry, manifest table, or backend-owned prompt store
is justified only when multiple behavior families need shared lifecycle,
authorization, retention, search, or rollout policy. Until then, prefer explicit
version fields and digests on the existing records that already own the
decision.

## Consequences

### Positive

- Research runs are replayable and auditable because the behavior used for each
  decision is snapshotted as bounded metadata.
- Methodology-source coverage can be regression tested without making the
  methodology catalog, skills, REST, MCP, and frontend each invent a separate
  source-completeness model.
- Prompt/schema/policy changes get mechanical contract tests instead of relying
  on prompt prose or broad model-output snapshots.
- Existing auth, validation, error, audit, logging, MCP, and policy layers remain
  the enforcement boundary.

### Negative

- Implementations must thread behavior coordinates through accepted commands and
  run-scoped records, even when the first version is `v1`.
- Catalog, schema, and prompt/template edits need targeted fixture or contract
  test updates; otherwise they are unreviewable scientific-behavior changes.
- Some behavior changes may require data migration or explicit compatibility
  handling when old runs resume under older coordinates.

### Risks

- If version coordinates are optional on a run path that affects stage legality,
  resume can silently use newer behavior than the original run.
- If source-completeness logic is duplicated outside research services, REST,
  MCP, and UI can disagree about whether methodology requirement extraction is
  complete.
- If tests only snapshot generated prose, they can either be brittle or miss
  schema/source-state regressions.
- If digests or locators are logged or exposed without bounding, local paths or
  unpublished research material can leak.

## Non-Goals

- No implementation of entities, migrations, controllers, DTOs, MCP tools,
  frontend views, parsers, source records, catalog loaders, prompt stores,
  policy runners, or tests in this ADR. (The "catalog loaders" clause is
  superseded by ADR-077, which makes the methodology catalog backend-owned,
  validated-on-load reference data; the remaining non-goals stand.)
- No generic workflow engine, behavior-execution engine, plugin marketplace,
  dynamic code loading, or backend-owned prompt marketplace.
- No storage decision for full text, PDFs, manuscripts, raw provider payloads,
  raw prompts, completions, or charting datasets.
- No new authentication model, actor override mechanism, error envelope, logging
  stack, enum-mirror system, GitHub side-effect path, token-in-argv path, or
  direct AGE write path.
- No requirement that historical runs be re-evaluated under newer behavior
  versions unless a future migration explicitly chooses that.

## Related Requirements

- `GC-RSCH-N015` - maintainability.
- `GC-RSCH-F005` - methodology catalog selection and rejected alternatives.
- `GC-RSCH-F006` - required primary methodology sources read before method
  requirements.
- `GC-RSCH-F007` - methodology requirements extracted without domain answers.
- `GC-RSCH-N001` - factuality.
- `GC-RSCH-N016` - scientific humility.

## Related Issues

- #1005 - Research methodology catalog and primary-source tracking.
- #1026 - Research automated review pipeline.
- #1027 - Research evaluation harnesses.
- #1029 - Research adapter/plugin boundary.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Catalog Curation.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-071 - Research Interoperability and Source Identity Boundary.
- ADR-072 - Research REST and MCP Tool Surface.
- ADR-073 - Research Extensibility and Adapter Boundary.
