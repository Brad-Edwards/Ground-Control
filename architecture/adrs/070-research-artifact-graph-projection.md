# ADR-070: Research Artifact Graph Projection

## Status

Accepted

## Date

2026-06-28

## Context

Issue #1003 and `GC-RSCH-R006` require reproducible research artifacts and
provenance to be visible in the Ground Control graph: protocol, search log,
source set, exclusions, access gaps, charting data, synthesis, decisions, and
final draft inputs must be inspectable as a reproducible chain. The graph must
also traverse from draft claims back to synthesis, charting cells, and sources.

The repository already has the source-of-truth model this work must build on:

- ADR-055 owns the skill-side research workflow and citation MCP.
- ADR-056 owns the `RESEARCH` project type and intake metadata.
- ADR-064 owns `ResearchRun`, lifecycle stages, gates, and
  `ResearchRunArtifact` checkpoint manifests.
- ADR-069 owns the run-scoped research provenance ledger:
  `ResearchProvenanceNode` and `ResearchProvenanceEdge`.
- ADR-062 owns AGE graph snapshot publication.
- The mixed graph already has `GraphProjectionContributor`,
  `GraphProjectionRegistryService`, `MixedGraphService`, `MixedGraphClient`,
  `GraphTraversalLimits`, `GraphIds`, and `GraphEntityType`.

Without a narrow graph decision, likely failure modes are: creating a second
research graph schema, writing AGE directly from research services, creating one
graph entity type for every research artifact noun, duplicating provenance
validation in a graph service, projecting raw research content into graph-wide
properties, or mixing current and superseded research chains in the default
visualization.

## Decision

### 1. Research graph participation is a projection, not new source-of-truth

Research graph data is derived from existing relational research aggregates by
one or more `GraphProjectionContributor` implementations. `ResearchRun`,
`ResearchRunArtifact`, `ResearchProvenanceNode`, and `ResearchProvenanceEdge`
remain authoritative.

Do not add a generic research `graph_node` / `graph_edge` table, do not make AGE
the write model, and do not add research-specific graph traversal endpoints for
issue #1003. The existing mixed graph routes remain the public query surface:

- `GET /api/v1/graph/visualization`
- `POST /api/v1/graph/subgraph/query`
- `POST /api/v1/graph/traversal/query`
- `POST /api/v1/graph/paths/query`

MCP access uses the existing graph helpers and `gc_query` `/api/v1/graph`
allowlist. This issue does not need a new MCP write tool or privileged side
effect path.

### 2. Use three bounded graph entity types

The implementation should add graph participation for the research domain with
three public `GraphEntityType` values:

- `RESEARCH_RUN` for the run context.
- `RESEARCH_ARTIFACT` for `ResearchRunArtifact` manifest rows.
- `RESEARCH_PROVENANCE_NODE` for `ResearchProvenanceNode` rows.

Do not create separate graph entity types for `PROTOCOL`, `SEARCH_LOG`,
`SOURCE_SET`, `EXCLUSION`, `ACCESS_GAP`, `CHARTING_DATA`, `SYNTHESIS`,
`DECISION`, or `FINAL_DRAFT_INPUT`. Those are already represented by
`ResearchArtifactType`, `ResearchRunStage`, and `ProvenanceNodeKind` values.
The graph type identifies the owning aggregate shape; artifact/provenance
semantics are properties.

R006's artifact vocabulary maps onto existing records as follows:

| R006 term | Existing authority |
|---|---|
| protocol | `ResearchArtifactType.PROTOCOL_PLAN` |
| search log | `ResearchArtifactType.SEARCH_LOG` |
| source set / exclusions | `ResearchArtifactType.SCREENING_RESULT` plus source provenance nodes |
| access gaps | `ProvenanceNodeKind.FULL_TEXT_ACCESS` |
| charting data | `ResearchArtifactType.CHARTING_DATA`, `CHARTING_CELL`, `EVIDENCE_MATRIX_CELL` |
| synthesis | `ResearchArtifactType.SYNTHESIS`, `SYNTHESIS_CLAIM` |
| decisions | gate/rationale/disclosure records remain authoritative; graph may reference their IDs but should not make them first-class nodes in this issue |
| final draft inputs | `ARGUMENT_MAP`, `MANUSCRIPT`, `ARGUMENT_MOVE`, `FINAL_PROSE` |

If later work needs decision records as traversable graph participants, add a
new ADR or amend this one rather than folding gate decisions into artifact or
provenance nodes.

### 3. Project only the current reproducibility chain by default

The mixed graph's default projection should show the current, active research
chain. `ACTIVE` artifact and provenance rows are included. `SUPERSEDED` or
`FAILED` research rows remain available through the research run/provenance APIs
and audit history, but they should not appear in the default mixed graph unless a
future history-specific graph view is designed.

This keeps path finding from a final prose node to source evidence from crossing
old artifact attempts by accident. Rework remains reproducible through
`ResearchRunArtifact` and provenance supersession metadata, not by mixing
historical attempts into the ordinary graph.

### 4. Preserve provenance direction while supporting graph traversal

Graph edges emitted from `ResearchProvenanceEdge` preserve ADR-069 direction:
upstream input node -> downstream output node. A final prose node can still be
traversed back to sources through the existing mixed graph traversal, which is
currently undirected at the `MixedGraphService` level.

Use `ProvenanceEdgeRelation.name()` as the graph edge type for provenance edges,
matching ADR-069's amendment for issue #1003. Because ADR-069 defines the
relation and direction together, consumers must interpret those edge types as
upstream input node -> downstream output node. Do not duplicate provenance cycle
checks or same-run validation inside graph projection; `ResearchProvenanceService`
owns write legality.

Run and artifact context edges should be structural and low-cardinality, for
example:

- `HAS_RESEARCH_ARTIFACT`: `RESEARCH_RUN` -> `RESEARCH_ARTIFACT`
- `ARTIFACT_HAS_PROVENANCE`: `RESEARCH_ARTIFACT` -> `RESEARCH_PROVENANCE_NODE`

Do not add reverse duplicate edges just to make backward reads easy. If a later
graph API needs directed traversal, extend the bounded mixed graph query contract
instead of inverting provenance semantics.

### 5. Graph properties are safe summaries, not artifact content

Graph node and edge properties are part of the public graph API and the AGE
materialized schema. They must stay bounded and low-cardinality.

Allowed shapes are identifiers, enum names, attempts, statuses, hashes, locators,
counts, and short labels needed for graph display. Avoid projecting
`summary`, query text, source abstracts, full-text excerpts, charting row bodies,
evidence matrices, manuscript prose, prompts, completions, provider payloads,
private absolute paths, bearer tokens, Zotero secrets, or arbitrary metadata
maps. Existing research APIs can expose bounded summaries under their own
contracts; the graph-wide projection should not widen that leakage surface.

Every new graph property key must be added to
`AgeGraphService.APPROVED_PROPERTY_KEYS` with focused tests. That allowlist is an
AGE schema contract, not an optional adapter detail.

### 6. Cross-cutting contracts remain shared

Security and project isolation stay on existing paths. Graph reads route through
`/api/v1/graph/**`, the shared `ApiPathMatrix`, `ProjectService.requireProjectId`,
and project-scoped repositories. Admin materialization remains
`/api/v1/admin/graph/materialize` and requires `ROLE_ADMIN`.

Validation stays layered: request DTO Bean Validation, `MixedGraphService`
defensive validation, `GraphTraversalLimits`, and AGE adapter caps/allowlists.
Unknown research graph entity types must fail through `DomainValidationException`
and the standard `ErrorResponse` envelope.

Logging stays with SLF4J and the existing request/actor MDC. Log counts, project
identifier, run id, graph entity type, artifact type, node kind, relation type,
and duration. Do not log graph property maps or raw research content.

This projection introduces no new configuration, secrets, subprocesses, shell
commands, network calls, provider calls, GitHub writes, or token-in-argv path.
Citation/provider/orchestration effects remain at ADR-055/ADR-028 boundaries and
re-enter the backend through structured research service writes.

### 7. Extensibility seam

The extension seam is the contributor's mapping inventory:

- `GraphEntityType` additions and public enum mirrors.
- `ResearchArtifactType` / `ProvenanceNodeKind` property mapping.
- `ProvenanceEdgeRelation.name()` as the provenance edge-type source.
- `AgeGraphService.APPROVED_PROPERTY_KEYS`.
- Frontend graph colors and API type mirrors.
- MCP/OpenAPI drift gates when a write or typed public enum is exposed.

Adding one new research artifact type, provenance node kind, or relation should
update this inventory and tests. It should not require a new graph subsystem,
new graph endpoints, or a new storage model.

## Consequences

### Positive

- Issue #1003 can make research provenance traversable through the existing mixed
  graph without duplicating ADR-069's relational ledger.
- The graph can show reproducible research chains while lifecycle gating,
  rationale, decision logs, disclosures, and historical rework remain under
  their owning research aggregates.
- AGE materialization, project scoping, traversal bounds, and frontend graph
  mirrors stay in the same repo-wide contracts used by other graph participants.

### Negative

- Consumers that need historical superseded chains must use the research APIs
  until a history-specific graph view is designed.
- `RESEARCH_ARTIFACT` and `RESEARCH_PROVENANCE_NODE` are coarse graph types; UI
  labeling must use properties to distinguish protocol/search/charting/claim
  nodes.
- The AGE property-key allowlist creates deliberate friction for every projected
  field.

### Risks

- Projecting `summary` or raw artifact content would turn the graph into a broad
  leakage surface for unpublished research and provider payloads.
- If provenance edges are reversed for display convenience, future directed
  graph traversal will contradict ADR-069.
- If superseded and active attempts are mixed in the default projection, shortest
  paths may silently cross obsolete evidence.
- If frontend or MCP enum mirrors are not updated with new graph entity types,
  graph filtering and visualization will degrade or fail policy gates.

## Non-Goals

- No implementation of entities, migrations, repositories, controllers,
  contributors, AGE queries, frontend views, or MCP tools in this ADR.
- No replacement of `ResearchRunArtifact`, `ResearchProvenanceNode`,
  `ResearchProvenanceEdge`, gate decision logs, rationale entries, disclosures,
  `EvidenceArtifact`, `TraceabilityLink`, or AGE snapshots.
- No full-text store, citation database, charting-row store, manuscript store,
  prompt/completion store, workspace parser, generic graph query language, or
  user-supplied Cypher.
- No new authentication model, actor override, exception hierarchy, error
  envelope, logging stack, policy runner, or secret-handling path.

## Related Requirements

- `GC-RSCH-R006` - reproducible research artifacts.
- `GC-RSCH-R004` - full provenance chain.
- `GC-RSCH-N002` - provenance.
- `GC-RSCH-N009` - interoperability.
- `GC-RSCH-N011` - observability.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-032 - AGE Query Construction Boundary.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-062 - AGE Graph Projection Snapshot Publication.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-069 - Research Artifact Provenance Ledger.
