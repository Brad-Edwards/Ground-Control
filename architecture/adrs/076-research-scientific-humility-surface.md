# ADR-076: Research Scientific Humility Surface

## Status

Accepted

## Date

2026-06-29

## Context

`GC-RSCH-N016` requires research outputs to expose negative results, failed
searches, access gaps, missing evidence, method limits, and non-claims. Existing
research ADRs already define the neighboring product surfaces:

- ADR-055 owns the skill-side workflow disciplines, including the two-state
  source rule, method-limit checks, non-claim handling, and no unsupported
  synthesis/prose claims.
- ADR-064 owns `ResearchRun`, stage legality, gate decisions, and artifact
  manifests.
- ADR-065 owns source/access-gap counts, bounded failure observations, and the
  observability snapshot.
- ADR-067 owns the rationale ledger for why methodology choices, search
  decisions, exclusions, charted values, synthesis claims, and writing claims
  exist.
- ADR-068 owns final-output accountability disclosure and unresolved
  uncertainty entries.
- ADR-069 owns the provenance ledger from query/source/full-text state through
  charting, synthesis, argument moves, and final prose.
- ADR-071 owns provider-neutral source identity and source disposition.
- ADR-072 owns the REST/MCP research surface and rejects adapter-side business
  logic.
- ADR-078 owns the backend methodology catalog and the rule that
  `METHODOLOGY_REQUIREMENTS` completion derives required-source coverage from
  backend-owned reference data, not caller-declared source lists.

Without a focused decision, likely failure modes are:

- adding a new `ResearchHumilityLedger` that duplicates rationale, provenance,
  source disposition, failure observations, and disclosure records;
- treating runtime failures, provider outages, no-result searches, missing
  evidence, and access gaps as the same concept;
- hiding method limits and non-claims in manuscript prose or workspace files
  while API/MCP/UI outputs show only positive findings;
- deriving humility status by parsing `search-log.md`, `synthesis.md`,
  manuscripts, transcripts, provider payloads, or local files at read time;
- storing raw search results, source text, charting rows, manuscript prose,
  prompts, provider errors, tokens, or private paths in broad API responses;
- letting frontend, MCP, or skill prompt text decide whether humility coverage
  is complete.

The design need is a bounded exposure contract, not a new research workflow
engine or raw-content store.

## Decision

### 1. Scientific humility is a composed research-run surface

Expose scientific humility as a run-scoped view composed from authoritative
research records. The v1 semantic authorities are:

- source/access-gap state from ADR-065 and ADR-071;
- search, exclusion, charting, synthesis, and writing rationale from ADR-067;
- final-output uncertainty disclosure from ADR-068;
- query/source/full-text/claim provenance from ADR-069;
- artifact attempt and supersession state from ADR-064;
- bounded run failure observations from ADR-065.

Do not create a new source-of-truth aggregate unless a later requirement proves
an independent lifecycle, retention policy, indexing need, or access-control
boundary that the existing research records cannot satisfy.

### 2. Use a closed humility category vocabulary

N016 categories must be represented with closed, API-visible vocabulary rather
than free-text labels:

| Category | Meaning |
|---|---|
| `NEGATIVE_RESULT` | Charted evidence or synthesis found no support, a null result, contradiction, or absence of expected pattern within the accepted corpus. |
| `FAILED_SEARCH` | A planned search/query/source-acquisition attempt produced no usable result or failed after the accepted retry/alternative policy. |
| `ACCESS_GAP` | A known candidate/source record could not be legitimately obtained as full text and therefore was not charted. |
| `MISSING_EVIDENCE` | A protocol-required evidence class, source role, claim term, or validation need remains unsatisfied by the accepted corpus. |
| `METHOD_LIMIT` | The selected methodology or run policy does not license a stronger claim such as prevalence, causation, severity, exhaustiveness, or quality weighting. |
| `NON_CLAIM` | A declared boundary of what the output does not assert or support, even if adjacent evidence exists. |

The extension seam is this category vocabulary plus stable subject keys that
point into existing artifacts, provenance nodes, rationale entries, source
records, gates, or disclosure entries. Adding an obvious future humility class
should be an enum/validator/rendering change, not a new table or parallel
workflow.

### 3. Keep adjacent concepts distinct

Implementations must preserve these distinctions:

- a provider outage or tool exception is a bounded run failure observation; it
  becomes a `FAILED_SEARCH` humility item only when the search attempt itself is
  accepted as exhausted or unusable for the research method;
- an `ACCESS_GAP` is a known source that was found but not fully available;
  `MISSING_EVIDENCE` is an unmet evidence need or source role;
- a `NEGATIVE_RESULT` is grounded in charted or synthesized evidence;
  `NON_CLAIM` is an explicit claim boundary;
- a `METHOD_LIMIT` is an epistemic/methodological limit, not a transient
  workflow error;
- access-gap counts in the observability snapshot are summary facts, not the
  complete source-level explanation.

For issue #1006, the `METHODOLOGY_REQUIREMENTS` artifact is the first durable
humility checkpoint. It remains a `ResearchRunArtifact` lifecycle manifest whose
completion is gated by ADR-078 source coverage, not a Ground Control
`Requirement` set and not a free-form markdown parser. The artifact contract may
expose selected method, rejected alternatives, extracted methodology
requirements, open gates, explicit deferrals, method limits, and non-claims only
as phase-1 methodology obligations grounded in primary methodology sources.
Paper/domain answers such as databases, query strings, domain definitions,
inclusion/exclusion details, charting fields, synthesis dimensions, and
source-set caps remain protocol-planning concerns.

Method limits and non-claims for the methodology-requirements artifact must be
artifact-attempt-scoped bounded metadata or rationale/disclosure references that
reuse the existing `ResearchRunRationaleEntry`, gate, disclosure, source, and
provenance surfaces. If implementation needs a machine-readable artifact body,
it must add one typed run-scoped artifact contract with closed category/source
reference validation in the research service. It must not mint product
requirements, create a parallel methodology-requirements ledger, or hide
validation in controllers, MCP handlers, frontend code, local workspace files,
or skill prose.

### 4. Outputs expose current-attempt humility state

Any API, MCP, UI, graph, or export surface that presents a research output as
complete should either include the current artifact attempt's humility summary
or link to the bounded run-scoped records from which it is derived.

The summary is metadata: category, stage, artifact type/attempt, subject key,
bounded summary, safe locator/hash/reference, provenance, actor where
server-side actor context applies, and timestamp. It must not store or emit raw
artifact content.

Rework follows ADR-064: if an artifact is superseded, humility facts tied to the
old attempt remain historical. Current output views must not silently mix facts
from superseded and active attempts.

### 5. Existing research surfaces remain authoritative

`ResearchRunArtifact` remains the lifecycle checkpoint and stage-gating
authority. Humility state does not decide stage advancement unless a service
explicitly validates it as part of an existing artifact, gate, disclosure, or
completion rule.

`ResearchRunRationaleEntry` remains the "why" ledger. Humility categories may
classify rationale entries or reference them, but they do not replace the
rationale schema.

`ResearchProvenanceNode` / `ResearchProvenanceEdge` remain the "from what"
derivation ledger. Humility entries may reference provenance nodes, but reads
must not reconstruct humility state by crawling workspace files or provider
responses.

`ResearchRunDisclosure` remains the final-output accountability authority.
Final manuscripts should draw unresolved uncertainty from the same accepted
bounded data rather than relying on prose-only disclosure.

### 6. Cross-cutting layers stay shared

- **Security and authorization:** routes stay under the ADR-026 bearer/browser
  chains and `ApiPathMatrix`. Reads and writes resolve a single project/run
  through existing project-scoped services; cross-project misses are concealed
  as `404`.
- **Validation:** REST DTOs use Bean Validation and Jackson enum binding; MCP
  mirrors use Zod and ADR-034 drift checks. Services own same-project checks,
  artifact-attempt consistency, category/reference compatibility, bounded
  summary validation, current-vs-superseded filtering, and content-leak guards.
- **Errors:** transport failures use `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`. Research failure observations
  remain product facts, not exception envelopes or stack traces.
- **Actor provenance and audit:** mutation actors come from `ActorFilter` /
  `ActorHolder` and Envers revision metadata. Clients, MCP tools, providers, and
  imported artifacts do not supply audit actors.
- **Logging:** use SLF4J with low-cardinality fields: project, run id, stage,
  artifact type, attempt, humility category, source disposition, stable error
  code, subject key hash/reference, and counts. Do not log raw summaries,
  queries, source rows, full text, prompts, manuscripts, provider payloads,
  bearer tokens, Zotero secrets, or private absolute paths.
- **Configuration and OS/runtime exposure:** this surface introduces no new
  secrets, subprocesses, shell-outs, GitHub writes, citation calls, provider
  calls, filesystem scans, or token-in-argv path. Provider/citation/filesystem
  effects stay behind ADR-028, ADR-055, ADR-071, and ADR-073 adapter boundaries
  and re-enter through structured service commands.
- **Persistence:** if implementation extends existing research records, use the
  audited aggregate pattern already used in `domain/research`: `BaseEntity`,
  Flyway migration plus audit shadow table, project/run-scoped indexes, and
  database-backed hard invariants where feasible.
- **Testing and policy:** controller additions need `@WebMvcTest` slices;
  service tests cover concept separation, current-attempt filtering,
  project-scope rejection, bounded summaries, and no raw-content leakage.
  API-visible enum mirrors follow ADR-034. Repo completion still runs
  `make policy`.

## Consequences

### Positive

- N016 becomes enforceable through one composed product surface instead of
  prose-only humility language.
- Existing research records keep their jobs: lifecycle, observability,
  rationale, disclosure, provenance, and source identity do not collapse into a
  polymorphic "humility" schema.
- UI, REST, MCP, export, and graph consumers can expose absence, limits, and
  non-claims without parsing workspace artifacts.

### Negative

- Implementations must carry explicit categories and subject keys for negative
  facts and limitations instead of relying on narrative text alone.
- Some humility facts will be projections over multiple records, so services
  must be careful about current-vs-superseded artifact attempts.

### Risks

- If failed searches and runtime errors are conflated, users may treat an
  operational outage as evidence absence.
- If access gaps and missing evidence are conflated, source-availability limits
  can be mistaken for protocol incompleteness or the reverse.
- If method limits and non-claims are not explicit, outputs can look complete
  while silently overclaiming what the method licenses.
- If raw research content is copied into humility summaries, this surface can
  leak unpublished manuscripts, private source text, provider data, or secrets
  through APIs, MCP, graph properties, logs, or error bodies.

## Non-Goals

- No implementation of entities, migrations, controllers, DTOs, MCP tools,
  frontend views, graph contributors, renderers, provider adapters, or workspace
  parsers in this ADR.
- No new workflow engine, quality-gate domain, approval engine, source store,
  document store, PDF store, claim-management engine, or provider integration.
- No replacement of ADR-055 skills/citation MCP, ADR-064 lifecycle state,
  ADR-065 observability, ADR-067 rationale ledger, ADR-068 disclosure, ADR-069
  provenance ledger, ADR-071 source identity, or ADR-072 REST/MCP boundary.
- No new authentication model, actor override mechanism, exception hierarchy,
  error envelope, logging stack, enum-mirror system, policy runner, GitHub
  side-effect path, or token-in-argv path.

## Related Requirements

- `GC-RSCH-N016` - scientific humility.
- `GC-RSCH-N001` - factuality.
- `GC-RSCH-N011` - observability.
- `GC-RSCH-N012` - explainability.
- `GC-RSCH-N013` - final-output accountability.
- `GC-RSCH-N015` - maintainability.

## Related Issues

- #1005 - Research methodology catalog and primary-source tracking.
- #1006 - Research methodology requirements artifact.
- #1014 - Research full-text acquisition and access-gap enforcement.
- #1016 - Research charting schema, pilot coding, and evidence spans.
- #1019 - Research thematic synthesis and conflict preservation.
- #1020 - Research method-limit and overclaim checks.
- #1024 - Research citation and prose grounding validation.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-028 - Temporal Workflow Orchestration Boundary.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Catalog Curation.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-065 - Research Run Observability Snapshot.
- ADR-067 - Research Explainability Rationale Ledger.
- ADR-068 - Research Final-Output Accountability Disclosure.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-071 - Research Interoperability and Source Identity Boundary.
- ADR-072 - Research REST and MCP Tool Surface.
- ADR-073 - Research Extensibility and Adapter Boundary.
- ADR-078 - Research Methodology Catalog as Backend Reference Data.
