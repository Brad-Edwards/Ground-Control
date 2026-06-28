# ADR-071: Research Interoperability and Source Identity Boundary

## Status

Accepted

## Date

2026-06-28

## Context

`GC-RSCH-N009` requires research interoperability with Zotero, Crossref,
OpenAlex, Unpaywall, arXiv, PubMed, DOI, BibTeX, RIS, CSL-JSON, Git, and local
markdown. Existing decisions already cover adjacent boundaries:

- ADR-055 owns the skill-side research workflow and deterministic citation MCP.
- ADR-064 through ADR-069 own research run lifecycle, observability, rationale,
  disclosure, and provenance ledgers.
- ADR-070 owns research projection into the mixed graph.
- ADR-026, ADR-032, ADR-033, ADR-034, and ADR-035 own REST access control, AGE
  query construction, audit actors, enum mirrors, and MCP adapter curation.

The remaining risk is concept confusion around "source": a provider payload, a
Zotero item, a DOI, a local markdown file, a BibTeX/RIS/CSL entry, a PDF, a
provenance node, and a graph node can all describe related material, but they
are not the same product object.

Likely failure modes without this decision are one schema per provider, Zotero
becoming the product source of truth, raw bibliographies or provider payloads in
broad APIs, domain services calling external providers directly, workspace-file
scans at read time, direct AGE writes, or duplicate validation/error/audit/MCP
logic for research sources.

## Decision

### 1. Canonical source identity is normalized and provider-neutral

When Ground Control persists durable research source state, it uses a
project/run-scoped research source record under `domain/research`. The source
record is a sibling of `ResearchRun`, `ResearchRunArtifact`,
`ResearchProvenanceNode`, and `ResearchRunRationaleEntry`; it is not a field
bolted onto any of them.

The record stores bounded metadata and stable identifiers only: canonical
display metadata, source type, normalized identifiers such as DOI, arXiv, PMID,
ISBN, OpenAlex id, Zotero key, or local source key, access/disposition state,
content hash, bounded locator, and adapter provenance. It does not store raw
provider payloads, full text, PDFs, charting rows, manuscripts, prompts,
completions, bearer tokens, Zotero secrets, Git credentials, or private absolute
paths.

Source records may be referenced by provenance nodes, artifact manifests,
rationale entries, review comments, disclosures, graph projection, or future
document/source-store records. Those surfaces remain authoritative for their own
jobs; source identity does not decide stage advancement, rationale completeness,
review resolution, disclosure completion, or graph traversal legality.

### 2. Provider adapters are deterministic ingress, not domain behavior

Crossref, OpenAlex, Unpaywall, arXiv, PubMed, DOI resolution, and Zotero calls
stay behind adapter boundaries. Today, ADR-055's `citation` MCP is the
deterministic adapter for bibliographic resolution and Zotero ingest. If a
future backend-owned integration is needed, it belongs in `infrastructure/`
behind a domain port and feeds normalized command DTOs into research services.

Domain services, controllers, graph contributors, MCP handlers, and frontend
components must not call providers directly. Provider responses are inputs to
normalization, not persisted contracts.

Provider credentials, Zotero API keys, polite-pool mailto settings, proxy
settings, cache settings, or offline-mode toggles use validated configuration or
MCP/server environment boundaries. Secrets must not be passed through process
argv, API payloads, Envers rows, logs, graph properties, MCP responses, or error
bodies.

### 3. Zotero is an external library mirror

Zotero integration records library, collection, item, and attachment identity as
external references and adapter provenance. A Zotero item can confirm or mirror
a source record, and a Zotero attachment can support full-text access state, but
Zotero is not Ground Control's canonical lifecycle, provenance, rationale, or
graph store.

The two-state source rule from ADR-055 remains binding: a source is either fully
in the review with full text read and charted, or an access gap and not charted.
Zotero metadata alone does not make a source chartable. Open-access attachment
policy remains adapter-owned; backend import paths must not silently attach or
store paywalled content.

### 4. BibTeX, RIS, CSL-JSON, and markdown are format adapters

BibTeX, RIS, CSL-JSON, and local markdown are import/export formats over
canonical source, artifact, and provenance records. They are not separate domain
schemas and must not each grow their own validators, lifecycle, or graph
projection.

Imports parse structured formats into normalized commands and accepted source
records. Exports render deterministic snapshots from canonical records using
stable ordering and stable identifiers. Import/export adapters must use
structured parsers or serializers where available, report bounded validation
errors through existing domain exceptions, and avoid echoing raw file content or
provider payloads in logs or error envelopes.

Offline mode may record unresolved or imported source records with clear adapter
provenance and disposition, but it must not fabricate provider confirmation. A
DOI-shaped string is an identifier claim until a deterministic adapter resolves
or imports metadata for it.

### 5. Git and local filesystem integration stores references, not authority

Git commits, repository-relative paths, local markdown files, and workspace
artifacts are locators or reproducibility anchors. They become product facts only
through explicit import actions that normalize them into source records,
artifact manifests, or provenance nodes.

Backend domain services must not shell out to Git, scan arbitrary workspaces, or
trust private absolute paths. Backend-owned Git or filesystem integration
belongs in an infrastructure adapter with configured roots, realpath/extension
validation, relative locators, content hashes, and bounded read sizes.

Git credentials and local paths must not leak through API responses, graph
properties, logs, Envers rows, MCP outputs, or error bodies.

### 6. Graph projection is derived from relational source of truth

Research source and provenance data appears in the mixed graph only through the
existing `GraphProjectionContributor` model and ADR-070's graph boundary.
Contributors read relational research records, emit `GraphNode` and `GraphEdge`
values, and let `AgeGraphService` own AGE SQL/Cypher construction, approved
property keys, snapshot publication, and traversal caps.

No research service, provider adapter, MCP handler, or frontend component writes
AGE directly or assembles Cypher. Graph projection must not duplicate
source/provenance schemas, fetch providers, read workspace files, or decide
lifecycle legality.

### 7. REST, MCP, and UI reuse existing contracts

Research interoperability writes go through controllers that resolve one project
through `ProjectService`, validate request DTOs with Bean Validation, and
delegate to research services. Services own semantic validation: project/run
scope, identifier compatibility, source-disposition rules, adapter provenance,
idempotency, rework/supersession, and content bounding.

Curated MCP writes, when added, mirror REST through flat Zod schemas, existing
`request` helpers, body-field allowlists, and the OpenAPI/MCP drift gates from
ADR-034. Reads may use `gc_query` only for allowlisted `/api/v1/**` paths. MCP
adapters must not parse workspace files, call providers, shell out to Git, or
implement a parallel source validator.

The web UI consumes backend source/provenance/readiness contracts. It must not
infer source state from BibTeX text, local markdown, Zotero screenshots, or
frontend-only heuristics.

### 8. Shared cross-cutting concerns remain binding

- **Security and authorization:** REST surfaces stay under ADR-026 bearer and
  browser chains. Cross-project misses are concealed as `404`. Admin-only
  synchronization, if added, must be explicit in `ApiPathMatrix`.
- **Actor provenance and audit:** mutation actors come from `ActorFilter` /
  `ActorHolder` and Envers metadata. Caller-supplied actor fields are not audit
  authority.
- **Validation:** request DTOs use Bean Validation; services own semantic
  checks; databases back hard uniqueness and foreign-key invariants.
  API-visible enums and MCP mirrors follow ADR-034.
- **Errors:** use `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`. Errors expose stable codes and
  field names, not raw file content, provider payloads, tokens, stack traces, or
  private paths.
- **Logging:** use SLF4J with low-cardinality fields: project, run id, source
  id, identifier type, provider, adapter action id, disposition, counts, and
  status. Do not log raw bibliographies, abstracts, full text, prompts, provider
  payloads, secrets, credential-bearing Git remotes, or absolute paths.
- **Configuration and OS/runtime exposure:** new network, provider, Zotero,
  Git, filesystem, cache, or offline knobs use validated
  `@ConfigurationProperties` or MCP-server environment contracts. No token-in-
  argv path.
- **Testing and policy:** controller additions need `@WebMvcTest` slices;
  service tests cover project scoping, idempotency, identifier normalization,
  provider/source conflict handling, disposition rules, content-leak guards, and
  graph projection bounds. Repo completion still runs `make policy`.

### 9. Extensibility seam

The seam is source identity vocabulary and adapter provenance, not a new
aggregate per provider or format. Obvious future variations should be handled by
closed vocabularies and validators:

- source identifier type: DOI, arXiv, PMID, ISBN, OpenAlex, Zotero, local key;
- acquisition channel: provider lookup, Zotero import, BibTeX/RIS/CSL import,
  local markdown import, Git snapshot, offline/manual entry;
- source disposition: candidate, included, excluded, full-text available,
  charted, access gap;
- bibliography format: BibTeX, RIS, CSL-JSON, markdown.

A first-class document store, source store, PDF store, search index, screening
engine, or Git synchronization aggregate is justified only when it has its own
lifecycle, retention, indexing, authorization, or storage requirements.

## Consequences

### Positive

- N009 gets one interoperability boundary for provider lookup, Zotero,
  local/offline work, bibliographic formats, graph projection, REST, MCP, and UI.
- Existing lifecycle, provenance, rationale, disclosure, and graph contracts
  remain separate instead of becoming one polymorphic source schema.
- Future providers or formats can be added by extending adapter provenance and
  identifier vocabularies rather than creating parallel schemas.

### Negative

- Implementations must normalize source/provider data up front instead of
  storing raw provider records and deciding later.
- Export fidelity is limited to canonical fields Ground Control accepts.
  Provider-specific fields that are not normalized remain outside the product
  contract unless a later ADR makes them first-class.

### Risks

- If source identifiers are not normalized consistently, duplicate prevention
  will fail across provider and format imports.
- If local/offline records are displayed without clear provenance/disposition,
  users may mistake unresolved imports for provider-confirmed sources.
- If summaries copy raw abstracts, full-text snippets, or provider payloads,
  research content and secrets can leak through APIs, logs, MCP responses, graph
  properties, or error envelopes.
- If graph projection starts reading providers or workspace files, the mixed
  graph can become nondeterministic and disagree with persisted provenance.

## Non-Goals

- No implementation of entities, migrations, controllers, DTOs, MCP tools,
  frontend views, provider adapters, parsers, exporters, or graph contributors
  in this ADR.
- No document store, PDF store, manuscript store, full-text index, or Zotero
  replacement.
- No generic workflow/orchestration engine, source-screening engine, or citation
  provider marketplace.
- No new authentication model, actor override, error envelope, logging stack,
  enum mirror system, policy runner, or direct AGE write path.

## Related Requirements

- `GC-RSCH-N009` - interoperability.
- `GC-RSCH-R004` - provenance chain.
- `GC-RSCH-R006` - reproducible research artifacts.
- `GC-RSCH-N002` - provenance.
- `GC-RSCH-N011` - observability.

## Related Issues

- #1003 - Research graph projection and traversal support.
- #1004 - Research REST and MCP tool surface.
- #1010 - Research source records and deterministic bibliographic resolution.
- #1011 - Research Zotero and source-store integration.
- #1025 - Research export formats.
- #1030 - Research local/offline execution mode.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-032 - AGE Query Construction Boundary.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Consolidation and Read-Only Query Escape Hatch.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-070 - Research Artifact Graph Projection.
