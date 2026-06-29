# ADR-069: Research Artifact Provenance Ledger

## Status

Accepted

## Date

2026-06-28

## Context

`GC-RSCH-R004` and issue #1002 require a durable provenance chain from user goal
to methodology source, query, candidate source, full text, charting cell,
synthesis claim, argument move, and final prose. Existing research decisions
already define adjacent but narrower surfaces:

- ADR-055 owns the skill-side workflow, citation MCP, methodology catalog,
  two-state source rule, evidence matrix, Argdown map, and manuscript drafting
  discipline.
- ADR-056 owns project type and research intake defaults.
- ADR-064 owns `ResearchRun`, lifecycle stages, gate policy, checkpoint/resume,
  and stage-level artifact manifests.
- ADR-065 owns the bounded observability snapshot.
- ADR-066 owns gate decision logs and review comments.
- ADR-067 owns the rationale ledger: why a choice, exclusion, charted value,
  synthesis claim, or writing claim exists.
- ADR-068 owns final-output accountability disclosure.

The current `ResearchRunArtifact` manifest is intentionally coarse: one durable
metadata record per lifecycle artifact type, with status, attempt, locator, hash,
actor, and supersession state. It proves that a stage produced the output needed
to unblock the next stage. It does not, and should not, answer fine-grained
questions such as which query found a source, which full-text span supports a
charting cell, or which charting cells support a final paragraph.

Without a focused provenance decision, likely failure modes are:

- bloating `ResearchRunArtifact` into a polymorphic source, charting, claim, and
  prose schema;
- treating `ResearchRunRationaleEntry` as provenance even though rationale
  answers "why" and provenance answers "from what";
- using `TraceabilityLink` for research-internal derivation links, confusing
  requirement traceability with run-local research provenance;
- promoting every research source or charting cell into the GRC
  `EvidenceArtifact` summary-evidence aggregate;
- parsing workspace files, citation-provider responses, local transcripts, or
  `decisions.md` on read to reconstruct provenance;
- treating Envers audit history as the business provenance graph;
- writing AGE graph rows directly instead of projecting from relational source
  of truth;
- storing raw prompts, completions, PDFs, full-text excerpts, charting rows,
  manuscript prose, private workspace paths, bearer tokens, Zotero secrets, or
  provider payloads in a broad provenance API.

## Decision

### 1. Provenance is a run-scoped research ledger

Add research provenance as a sibling aggregate under the existing research domain
boundary. It belongs to one `ResearchRun` and is project-scoped through that run.
It is not a field on `ResearchRun`, `ResearchRunArtifact`, `ResearchRunGate`,
`ResearchRunRationaleEntry`, `ResearchRunDisclosure`, `EvidenceArtifact`,
`TraceabilityLink`, or `WorkflowRun`.

The ledger follows the repository's durable aggregate pattern: `BaseEntity`,
audited JPA records, Flyway migrations plus audit shadow tables, project/run
scoped repository queries, service-owned writes, and DTOs/controllers that
delegate lifecycle and consistency rules to the domain service.

### 2. The ledger records provenance nodes and derivation edges

The semantic model is a directed run-local derivation graph:

- a provenance node identifies a bounded research referent;
- a provenance edge says one node contributed to, supported, selected, cited, or
  derived another node;
- the edge direction is from upstream input to downstream output, so a final
  prose node can be traversed backward to the user goal and source evidence.

Initial node kinds must cover the R004 chain:

- user goal or intake snapshot;
- methodology source;
- protocol or query;
- candidate source or source record;
- full-text access state;
- charting cell;
- evidence-matrix cell;
- synthesis claim;
- argument move;
- final prose locator.

The node-kind and edge-relation vocabularies are closed API-visible enums. Adding
new kinds or relations follows ADR-034 mirror/drift rules. Free-text phase names,
skill phase numbers, workspace filenames, or `workflow_phase_event.phase` values
are not semantic authorities.

Nodes store stable references and bounded summaries only: run, stage, optional
`ResearchRunArtifact` id and attempt, kind, subject key, optional artifact-relative
locator, optional hash/fingerprint, optional external identifier such as DOI,
Zotero key, query id, source id, charting field, claim id, argument id, section
key, or paragraph key, and actor/provenance metadata. Edges store node ids,
relation type, optional bounded role, optional confidence or limitation summary,
and provenance metadata.

The ledger must not store raw artifact content. Full query text, source PDFs,
full-text excerpts, abstracts used as charting substitutes, charting-row payloads,
evidence matrices, manuscript prose, prompts, completions, provider responses, or
private absolute paths remain outside this ledger. If a complete artifact is
needed, the ledger points to the manifest, workspace artifact, external source,
or future document/source store.

### 3. Existing research surfaces remain authoritative for their own jobs

`ResearchRunArtifact` remains the lifecycle checkpoint and stage-gating
authority. A provenance node may reference an artifact manifest row and attempt,
but the provenance ledger does not decide whether a stage may advance.

`ResearchRunRationaleEntry` remains the explanation surface for why a choice or
claim exists. It may reference provenance nodes when useful, but it is not the
source-of-truth derivation graph.

`ResearchRunGate`, gate decision logs, review comments, and final-output
disclosures keep the boundaries from ADR-064, ADR-066, and ADR-068. They may
inform or reference provenance, but lifecycle advancement, review resolution,
human approval, and disclosure completeness are not derived from provenance rows.

`EvidenceArtifact` remains the GRC summarized-evidence aggregate from ADR-045. A
research artifact may later be promoted or linked to `EvidenceArtifact`, but R004
does not turn every query, source, charting cell, or claim into a GRC evidence
summary.

`TraceabilityLink` remains the requirement-to-artifact traceability contract. It
can link `GC-RSCH-R004` to this ADR, issue #1002, PRs, tests, or API artifacts,
but it is not the internal source-to-cell-to-claim provenance graph for one
research run.

### 4. Writes are append-only and rework-aware

Provenance is historical product state. Accepted nodes and edges are not edited
in place to describe a replacement artifact or revised claim. Rework creates new
nodes and edges tied to the new artifact attempt or subject key, while prior
nodes and edges remain queryable as historical provenance.

The ledger must support supersession/replacement metadata so reads can distinguish
current provenance for the active artifact attempt from historical provenance for
superseded attempts. This metadata follows ADR-064's artifact rework model rather
than inventing a second lifecycle.

External callers that may retry, including MCP tools and future orchestrators,
submit a bounded run-scoped idempotency key or source action id. Replays with the
same identity return the existing node/edge set or fail as a real conflict if the
payload is incompatible.

### 5. Validation is shared and defense-in-depth

REST request DTOs use Bean Validation for required fields, enum binding, length
bounds, collection bounds, and UUID shapes. Services own the semantic rules:

- the run exists in the resolved project;
- referenced artifacts, rationale entries, decision logs, disclosures, or future
  document/source records belong to the same run/project;
- node kind and reference fields are compatible;
- stage, artifact type, and artifact attempt are consistent;
- edge endpoints belong to the same run;
- self-edges and derivation cycles are rejected;
- superseded/current reads do not silently mix artifact attempts;
- bounded summaries do not echo raw research content;
- idempotency conflicts are deterministic.

Database constraints should back hard invariants where feasible: foreign keys,
same-run uniqueness for idempotency keys, check constraints for enum-backed
status values, and indexes for run/kind/subject-key and edge traversal.

### 6. Reads and graph traversal stay bounded

Default reads are project-scoped, run-scoped, paged or otherwise bounded, and
return safe summaries plus stable references. A caller must be able to ask for
the provenance chain of a final prose locator or synthesis claim and traverse
back to charting cells, full-text access, candidate sources, queries,
methodology source, and user goal without reading workspace files.

The relational ledger is the source of truth. If issue #1003 projects research
provenance into Apache AGE, it must do so through the existing graph projection
contributor model. AGE SQL/Cypher construction remains inside the AGE adapter
per ADR-032, with allowlisted labels/relations, parameter binding where
available, traversal depth caps, and snapshot publication semantics from
ADR-062. Controllers, services, MCP handlers, and frontend components must not
write AGE directly or assemble Cypher.

### 7. Cross-cutting layers stay shared

- **Security and authorization:** routes stay under `/api/v1/**` and the
  ADR-026 bearer/browser chains plus `ApiPathMatrix`. Reads and writes resolve a
  single project through `ProjectService`; cross-project misses are concealed as
  `404`.
- **Actor provenance and audit:** authenticated mutation actors come from
  `ActorFilter` / `ActorHolder` and Envers revision metadata. Caller-supplied
  `actor`, `author`, or `approver` fields are not accepted.
- **Content provenance:** tool, adapter, model/provider, citation service,
  Zotero key, source action id, or import provenance may be stored as bounded
  descriptive metadata. It does not authenticate the caller and cannot override
  the audit actor.
- **Errors:** use existing `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`. Error details use stable codes
  and field names; they do not echo query bodies, source content, charting rows,
  manuscript text, prompts, provider responses, tokens, or paths.
- **Logging:** use SLF4J with low-cardinality fields such as project, run id,
  stage, node kind, relation type, artifact type, attempt, source action id, and
  counts. Do not log raw summaries, queries, source rows, full text, prompts,
  manuscripts, provider payloads, bearer tokens, Zotero secrets, or private
  absolute paths.
- **Configuration and OS/runtime exposure:** this feature introduces no new
  secrets, subprocesses, shell-outs, network calls, GitHub writes, citation
  calls, or token-in-argv path. Citation/provider/orchestration effects stay at
  ADR-055/ADR-028 adapter boundaries and re-enter through structured service
  writes.
- **MCP:** read access may use allowlisted `gc_query` routes. Curated writes, if
  added, mirror REST through flat Zod schemas, existing request helpers, and
  ADR-034/OpenAPI drift gates. MCP handlers must not parse workspace files,
  perform provider calls, or implement a parallel provenance validator.
- **Testing and policy:** controller additions need `@WebMvcTest` slices;
  service tests cover project scoping, same-run references, cycle rejection,
  superseded/current filtering, idempotency, and content-leak guards; migrations
  get smoke coverage; API-visible enum mirrors follow ADR-034; repo completion
  still runs `make policy`.

### 8. Extensibility seam

The extension seam is the node-kind / reference-shape / edge-relation vocabulary,
not a new table or controller per research phase. Future work such as issue
#1022's argument claim ledger, source-level drill-down, taxonomy iteration
rationale, peer-review response claims, or a document store should either:

- reference existing provenance nodes by id; or
- add a new node kind/relation plus validation and rendering support; or
- introduce a first-class source/document/claim aggregate only when it has its
  own lifecycle, retention, indexing, access-control, or storage needs.

The ledger must be able to point at future first-class records by UUID without
rewriting historical provenance rows, while still supporting external identifiers
for sources that are intentionally outside Ground Control.

### Amendment 2026-06-28: Mixed-graph projection for issue #1003

Issue #1003 projects the existing research provenance ledger into the mixed
Ground Control graph. The relational ledger from this ADR remains the source of
truth; Apache AGE and `/api/v1/graph/**` receive a projection only. The projection
must use the existing `GraphProjectionContributor` model and must not introduce a
second provenance graph, direct AGE writes, workspace-file parsing, or
provenance-specific graph endpoints.

The graph identity should follow the repository's aggregate-oriented graph
vocabulary:

- `ResearchProvenanceNode` projects as a first-class mixed-graph node with one
  aggregate-level `GraphEntityType`, not one `GraphEntityType` per
  `ProvenanceNodeKind`.
- The node's PROV-like role is represented by bounded properties such as
  `provenanceKind`, `provCategory`, `stage`, `artifactType`, `attemptNo`,
  `status`, `contentHash`, `externalIdentifier`, `toolName`, `toolVersion`,
  `sourceActionId`, `actor`, and creation/update timestamps where useful.
- `ResearchProvenanceEdge` projects from upstream input to downstream output
  using `ProvenanceEdgeRelation.name()` as the graph edge type, preserving the
  ledger direction already defined in this ADR. Edge properties may carry only
  bounded metadata such as `role`, `status`, `actor`, and timestamps.
- Agent identity remains audit/provenance metadata until the backend owns a
  first-class user/agent aggregate. Do not create synthetic graph nodes for
  actors, tools, providers, or citation systems in this slice.

The mixed graph's default current-state view should project `ACTIVE` provenance
records. Superseded rows remain available through the run-scoped provenance API
and Envers audit history; projecting historical and current chains together into
the default graph would create ambiguous traversal answers. If a later feature
needs historical graph traversal, add an explicit bounded query/filter contract
instead of changing the default projection semantics.

The implementation must update the graph participant inventory as one bounded
slice: `GraphEntityType`, graph projection contributor, project-scoped repository
queries, `AgeGraphService.APPROVED_PROPERTY_KEYS`, frontend/API enum mirrors if
the entity type is user-visible there, and focused contributor / AGE-registry /
`@WebMvcTest` coverage. Do not model provenance nodes as `EvidenceArtifact`,
`Document`, `TraceabilityLink`, `ResearchRunArtifact`, or requirement relations
for graph convenience. Those surfaces keep their ADR-defined jobs.

The architecture preflight for issue #1003 is recorded in
`architecture/notes/research-provenance-graph-projection-preflight.md`.

## Consequences

### Positive

- `GC-RSCH-R004` gets one durable, queryable provenance chain instead of
  reconstructing provenance from manifests, rationale text, workspace files, and
  audit history.
- Existing lifecycle, rationale, review, disclosure, traceability, evidence, and
  graph-projection boundaries remain clear.
- Rework preserves historical chains while allowing current-chain reads for the
  active artifact attempt.
- Future graph projection and argument-claim work have a stable relational
  source instead of inventing separate provenance schemas.

### Negative

- Issue #1002 needs more than extending `ResearchRunArtifact`; it needs durable
  fine-grained nodes and edges with migration, API, DTO, and test coverage.
- The ledger stores references and bounded summaries, not complete content.
  Users who need the exact charting row, PDF span, or prose must open the
  referenced artifact or a future content store.
- Cycle checks and current-vs-historical traversal add service complexity that a
  simple manifest list would not have.

### Risks

- If subject keys are unstable within an artifact attempt, provenance rows can
  become technically valid but unusable for audit.
- If broad reads include raw research content, the provenance API becomes a
  leakage surface for unpublished work, private libraries, provider payloads, or
  credentials.
- If graph projection becomes the write authority, AGE and relational state can
  disagree.
- If rationale and provenance are collapsed, the system may explain why a claim
  was made without being able to prove which source chain supports it.
- If Envers is treated as business provenance, historical mutation audit will be
  confused with research derivation.

## Non-Goals

- No implementation of entities, migrations, controllers, DTOs, MCP tools,
  frontend views, graph contributors, or workspace parsers in this ADR.
- No full-text store, PDF store, citation database, search-result store,
  charting-row store, manuscript store, prompt/completion store, or document
  annotation platform.
- No replacement of ADR-055 skills/citation MCP, ADR-064 lifecycle and artifact
  manifests, ADR-065 observability, ADR-066 gate/review records, ADR-067
  rationale ledger, ADR-068 disclosures, ADR-045 `EvidenceArtifact`, or
  ADR-011 `TraceabilityLink`.
- No generic graph database API, user-supplied Cypher, workflow engine, provider
  adapter, background scheduler, or privileged GitHub side effect.
- No new authentication model, actor override mechanism, error envelope, logging
  stack, enum mirror system, policy runner, or secret-handling path.

## Related Requirements

- `GC-RSCH-R004` - full provenance chain.
- `GC-RSCH-R006` - reproducible research artifacts.
- `GC-RSCH-F019` - charting forms with field-level provenance.
- `GC-RSCH-F024` - evidence matrices linking source IDs to charted fields, codes,
  and synthesis claims.
- `GC-RSCH-N002` - provenance.
- `GC-RSCH-N003` - reproducibility.
- `GC-RSCH-N004` - auditability.

## Related ADRs

- ADR-011 - Requirements Data Model.
- ADR-026 - REST API Access Control.
- ADR-032 - AGE Query Construction Boundary.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Catalog Curation.
- ADR-045 - Evidence Derivation and Temporal State History.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-065 - Research Run Observability Snapshot.
- ADR-066 - Research Gate Decision Log and Review Comments.
- ADR-067 - Research Explainability Rationale Ledger.
- ADR-068 - Research Final-Output Accountability Disclosure.
