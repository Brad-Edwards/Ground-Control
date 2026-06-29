# Research Provenance Graph Projection Preflight

Issue: #1003
Requirements: GC-RSCH-R004, GC-RSCH-R006, GC-RSCH-N002, GC-RSCH-N009,
GC-RSCH-N011

This note records architecture guardrails for projecting research runs,
research artifacts, and research provenance into the mixed Ground Control graph.
It is not an implementation plan.

## Boundary

The source of truth is the existing run-scoped research provenance ledger from
ADR-069: `ResearchProvenanceNode` and `ResearchProvenanceEdge`, owned by
`ResearchProvenanceService` and persisted through the research repositories.
Graph work for #1003 is a projection of that ledger, not a new provenance store,
not an AGE write path, not a parser over research workspace files, and not a
replacement for the run-scoped provenance REST API.

Use the mixed graph contract already used by requirements, evidence, documents,
architecture-model elements, risks, controls, findings, and audits:
`GraphEntityType`, `GraphIds`, `GraphNode`, `GraphEdge`, `GraphProjection`,
`GraphProjectionContributor`, `GraphProjectionRegistryService`,
`MixedGraphService`, `MixedGraphClient`, and `GraphTraversalLimits`.

Per ADR-070, issue #1003 has three graph participants:

- `RESEARCH_RUN` for `ResearchRun`.
- `RESEARCH_ARTIFACT` for `ResearchRunArtifact` manifest rows.
- `RESEARCH_PROVENANCE_NODE` for `ResearchProvenanceNode` rows.

Do not add graph entity types for every `ResearchArtifactType`,
`ResearchRunStage`, or `ProvenanceNodeKind`; that would confuse domain aggregate
identity with lifecycle, artifact, and PROV role vocabulary. Those semantics
belong in bounded properties (`artifactType`, `stage`, `provenanceKind`,
relation metadata, actor, timestamps). Agent/tool/provider identity stays
metadata unless a later ADR introduces a first-class agent aggregate.

## Incumbents To Reuse

- Research ledger: `ResearchProvenanceNode`, `ResearchProvenanceEdge`,
  `ProvenanceNodeKind`, `ProvenanceEdgeRelation`, `ProvenanceRecordStatus`,
  `ResearchProvenanceNodeRepository`, and `ResearchProvenanceEdgeRepository`.
- Research lifecycle context: `ResearchRun`, `ResearchRunArtifact`,
  `ResearchArtifactType`, and `ResearchRunStage` remain authoritative for run
  scope, stage, artifact type, attempt number, and checkpoint state.
- Graph projection: existing contributor pattern and graph IDs
  (`GraphIds.nodeId(GraphEntityType, UUID)`), not entity-specific graph
  endpoints.
- Graph traversal: `/api/v1/graph/**` through `GraphController` and
  `MixedGraphService`; legacy `/api/v1/requirements/graph/**` remains
  requirement-only.
- AGE boundary: `AgeGraphService` owns SQL/Cypher construction, snapshot
  publication, property-key approval, parameter binding, and AGE-disabled
  fallback behavior.
- API and error handling: Bean Validation, service-level semantic validation,
  `GroundControlException` subclasses, `GlobalExceptionHandler`, and
  `ErrorResponse`.
- Audit and logging: `ActorFilter`, `ActorHolder`, Envers, `RequestLoggingFilter`,
  MDC, and SLF4J low-cardinality logs.

## Cross-Cutting Layers

- Auth: graph reads stay under `/api/v1/graph/**` and pass through
  `ApiPathMatrix` as authenticated routes. Graph materialization remains
  `/api/v1/admin/graph/materialize` and requires `ROLE_ADMIN`.
- Project isolation: every graph read resolves one project before building or
  traversing a projection. Provenance repository reads must be project-scoped
  through the owning `ResearchRun`; cross-project or cross-run nodes are absent
  from the projection rather than discoverable.
- Input bounds: root node count, node identifier length, entity-type filter
  length, depth, projection size, and path-result caps stay centralized in
  `GraphTraversalLimits` and enforced at DTO, service, and AGE-adapter layers.
- AGE safety: labels come from `GraphEntityType`, edge labels from the closed
  `ProvenanceEdgeRelation` enum, and property keys from
  `AgeGraphService.APPROVED_PROPERTY_KEYS`. Values are bound through the adapter,
  never interpolated into SQL or Cypher.
- Error envelopes: validation and not-found failures return the standard
  `ErrorResponse` shape. Do not add provenance-graph error wrappers or leak SQL,
  Cypher, enum class names, prompts, source text, summaries, provider payloads,
  tokens, private paths, or full property maps.
- Logging and audit: log counts, run IDs, node kinds, relation types, and
  projection lifecycle events only. Do not log research content, charting rows,
  manuscript prose, raw summaries, prompts, source excerpts, provider responses,
  tokens, or workspace paths.
- Config and runtime exposure: #1003 needs no new env vars, secrets,
  subprocesses, shell-outs, GitHub writes, citation-provider calls, network
  calls, or token-in-argv path. Existing graph and research configuration
  boundaries remain sufficient.

## Extensibility

The extension seam is the graph participant inventory plus the provenance
vocabulary:

- add the three ADR-070 aggregate-level graph entity types for this slice;
- project bounded node/edge properties needed for traversal and inspection;
- use `ProvenanceNodeKind` / `ProvenanceEdgeRelation` for PROV-like semantics;
- keep property keys small, stable, and allowlisted in AGE;
- add future provenance roles by extending the closed enums and their mirrors,
  not by creating a new table or graph subsystem per research phase.

The default mixed graph should project current provenance (`ACTIVE` records).
Historical chains stay available through the provenance API and Envers. If a
future workflow needs historical graph traversal, add an explicit bounded
historical-read parameter rather than silently mixing superseded and active
chains.

## Gotchas And Anti-Patterns

- Do not model research provenance nodes as `EvidenceArtifact`, `Document`,
  `TraceabilityLink`, `ResearchRunArtifact`, requirement relations, or generic
  graph rows for convenience.
- Do not introduce a universal `GraphParticipant` superclass, `graph_edge`
  table, or second provenance graph schema.
- Do not add one `GraphEntityType` per `ProvenanceNodeKind`; keep aggregate
  identity and PROV role separate.
- Do not parse workspace artifacts, local transcripts, `decisions.md`, citation
  provider responses, or manuscript files to build the graph.
- Do not project raw queries, full text, charting rows, evidence matrices,
  prompts, completions, provider payloads, private file paths, or secrets.
- Do not make AGE the source of truth or write AGE rows from research services.
- Do not add property keys without updating the AGE allowlist and a regression
  test that pins the key set.
- Do not make graph traversal bypass the existing project-scope, validation,
  error-envelope, or traversal-bound layers.

## Non-Goals

- No implementation of #1003 in this note.
- No new JPA aggregate, migration, controller, MCP tool, frontend screen, AGE
  query language, or graph endpoint solely from this preflight.
- No change to research stage gating, rationale ledgers, review comments,
  disclosures, evidence promotion, requirement traceability, or workflow
  automation.
- No representation of agents/tools/providers as first-class graph nodes until
  the backend owns a real aggregate with lifecycle, access-control, and query
  requirements.
