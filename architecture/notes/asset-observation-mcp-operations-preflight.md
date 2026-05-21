# Asset And Observation MCP Operations Preflight

Issue: #730
Requirement: GC-L008

This is architecture guardrail guidance for MCP parity over graph-native asset,
topology, observation, and evidence operations. It is not an implementation
plan.

## Boundary

GC-L008 is an MCP adapter requirement over existing REST/domain behavior. The
Spring REST API remains the semantic authority; MCP must not become a second
controller layer or a second validation hierarchy.

Keep these concepts separate:

- `OperationalAsset` is asset identity, classification, ownership/scope, subtype
  metadata, lifecycle, and knowledge state.
- `AssetRelation` is asset-to-asset topology. It is not a cross-entity link and
  it is not an observation.
- `AssetLink` is asset-to-other-entity linking through
  `GraphTargetResolverService`; it must keep internal UUID targets separate from
  external string identifiers.
- `Observation` is a time-bounded state fact about an asset. The latest/current
  state surface is a read projection over observation history.
- `EvidenceArtifact` is an append-only summarized-evidence aggregate with source
  references and supersession; it is not an update path for observations.
- The mixed graph (`GraphEntityType`, `GraphIds`, projection contributors, and
  `MixedGraphService`) is the traversal/query surface over canonical JPA data.
  Do not write AGE rows or graph-only records from MCP operations.

## Incumbents To Reuse

- REST controllers and DTOs: `AssetController`, `ObservationController`,
  `EvidenceArtifactController`, `GraphController`, and their request/response
  records. MCP field lists must mirror these records, not older docs or local
  naming guesses.
- Domain services and command DTOs: `AssetService`, `AssetTopologyService`,
  `ObservationService`, `EvidenceArtifactService`,
  `Create*Command` / `Update*Command`, and `ProjectService`.
- Project-scoped repositories:
  `OperationalAssetRepository`, `AssetRelationRepository`,
  `AssetLinkRepository`, `AssetExternalIdRepository`, `ObservationRepository`,
  and `EvidenceArtifactRepository`.
- Graph seams: `GraphTargetResolverService`, `GraphTraversalLimits`,
  `GraphProjectionContributor`, `AssetGraphProjectionContributor`,
  `EvidenceArtifactGraphProjectionContributor`, and `MixedGraphService`.
- MCP adapter seams: `request`, `buildUrl`, `addAuthorizationHeader`,
  `RequestError`, `parseErrorBody`, `pick`, `reqArg`, `toCamelCase` /
  `TO_CAMEL`, `link-create.js`, `gc-query.js`, `gc-evidence.js`, and the
  consolidated `gc_asset`, `gc_observation`, `gc_evidence`, and `gc_graph`
  surfaces.
- Docs and gates: `docs/API.md`, `mcp/ground-control/README.md`,
  adapter-level MCP tests, controller `@WebMvcTest` slices, and `make policy`.

## Cross-Cutting Layers

- Security: every backend call issued by MCP must enter the ADR-026 path matrix
  under `/api/v1/**`, using `addAuthorizationHeader` and the repo-local token
  resolution. MCP must not accept caller-supplied headers, bearer tokens,
  methods, absolute URLs, or admin paths outside the existing `GC_MCP_ADMIN`
  catalog gate.
- Actor provenance: backend mutations flow through `ActorFilter`,
  `ActorHolder`, MDC `actor_id`, and Envers. MCP may send its standard
  `X-Actor: mcp-server` fallback, but production actor identity comes from the
  authenticated principal. Do not add request-body actor fields or MCP actor
  override arguments.
- Request validation: Zod schemas enforce MCP argument shape and enum values;
  `reqArg` enforces action-required fields; `pick` enforces per-action body
  allowlists; Jackson enum binding and Bean Validation own REST DTO shape; domain
  services own semantic rules such as same-project checks, duplicate detection,
  subtype metadata validation, observation temporal checks, evidence source
  dual-mode validation, and supersede-once behavior.
- Error envelopes: backend errors must pass through `GlobalExceptionHandler` and
  `ErrorResponse`; MCP errors should reuse `RequestError` / `parseErrorBody`.
  Do not create MCP-only exception codes for domain failures that the backend
  already returns.
- Logging and observability: use the existing request logs, actor MDC, Envers,
  and low-cardinality service logs. Do not log raw observation values, evidence
  summaries, subtype metadata, external identifiers that may contain secrets,
  request bodies, authorization headers, or tokens.
- Graph cost and injection limits: traversal/query operations must use the
  existing REST graph endpoints, `GraphTraversalLimits`, `MixedGraphService`,
  and ADR-032 AGE construction boundary. No Cypher passthrough, no dynamic graph
  labels from MCP input, no unbounded depth/root/entity-type lists.
- `gc_query` boundary: pure reads that are not curated actions should use
  `gc_query` with relative allowlisted paths and flat primitive params. Write
  parity belongs in action-discriminated entity tools, not a generic
  `method`/`body` escape hatch.
- Enum and field mirrors: any API-visible enum or request field exposed through
  MCP must use the canonical Java enum/DTO vocabulary and update JS constants,
  Zod schemas, `TO_CAMEL`, README/API docs, and adapter tests together.

## Extensibility Seams

- The entity-tool seam is action-discriminated. Extend `gc_asset` /
  `gc_observation` only with action names that map to existing REST semantics;
  keep reads on `gc_query` unless the operation is write-like or compute-heavy.
- The field-mapping seam is the per-action body allowlist plus `TO_CAMEL`.
  Add a REST DTO field once at this seam and test the forwarded backend body.
  Do not rely on recursive camelization for user-defined metadata maps.
- The link seam is `link-create.js` plus `GraphTargetResolverService`. MCP should
  expose both `target_entity_id` and `target_identifier`, require only
  `target_type` and `link_type`, and let the backend decide which target mode is
  valid for the chosen type.
- The graph seam is node IDs of the form `GraphEntityType:UUID`, entity-type
  filters, and bounded traversal depth. Future graph-native entity types should
  join through a `GraphProjectionContributor`, not through bespoke MCP graph
  serialization.
- The state-aware query seam is low-cardinality canonical state already on the
  model: `knowledgeState` for assets/relations, observation `observedAt` /
  `expiresAt`, evidence `derivedAt` / `supersededByArtifactId`, and existing
  type/category filters. If more state facets are needed, add them to the
  canonical REST/domain surface first, then mirror to MCP.

## Gotchas And Anti-Patterns

- Do not mirror old observation names. The current REST DTO uses
  `observationKey`, `observationValue`, `source`, `observedAt`, `expiresAt`,
  `confidence`, and `evidenceRef`; MCP snake_case must map to those exact
  fields. `title`, `statement`, `valid_until`, and arbitrary `metadata` are not
  the current observation contract.
- Do not implement REST parity by adding one MCP tool per endpoint. ADR-035's
  consolidated surface is binding: one action-discriminated entity tool plus
  `gc_query` for pure reads.
- Do not duplicate project-scoping checks, target validation, subtype metadata
  validation, evidence-source validation, observation temporal validation, graph
  bounds, error envelopes, audit writers, or security filters in MCP.
- Do not use deprecated UUID-only service overloads on any new backend path.
  MCP must pass `project` through to REST and let controllers/services enforce
  same-project access.
- Do not model observations as topology, topology as observations, external IDs
  as unknown dependencies, or evidence artifacts as mutable observation
  summaries.
- Do not add generic path/method/body write proxies, Cypher passthrough, custom
  auth headers, caller-supplied token args, shell-outs, or token-in-argv flows.
- Do not expose admin graph materialization through default MCP sessions; it
  belongs behind the existing `gc_admin` / `GC_MCP_ADMIN` gate.

## Non-Goals

- No implementation of GC-L008 behavior in this preflight note.
- No new REST endpoints, migrations, graph schema, AGE adapter, frontend views,
  importers, scanners, schedulers, or workflow engine.
- No replacement of `OperationalAsset`, `AssetRelation`, `AssetLink`,
  `Observation`, `EvidenceArtifact`, `GraphTargetResolverService`,
  `MixedGraphService`, `gc_query`, or the consolidated MCP tool model.
- No change to ADR-026 security, ADR-032 AGE query construction, ADR-033 actor
  provenance, ADR-034 enum mirror policy, ADR-035 MCP curation, ADR-045 evidence
  append-only semantics, or ADR-046 partial-knowledge semantics.
