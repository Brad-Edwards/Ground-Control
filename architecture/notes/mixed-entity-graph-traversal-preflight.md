# Mixed Entity Graph Traversal Preflight

Issue: #728
Requirement: GC-G008

This note records architecture guardrails for mixed-entity graph traversal,
path finding, subgraph extraction, and visualization. It is not an
implementation plan.

## Boundary

The query surface for GC-G008 is the existing mixed graph contract:
`GraphProjection`, `GraphNode`, `GraphEdge`, `GraphEntityType`,
`GraphIds`, `GraphProjectionContributor`, `GraphProjectionRegistryService`,
`MixedGraphClient`, `MixedGraphService`, and `GraphTraversalLimits`.

`GraphController` exposes the mixed graph through:

- `GET /api/v1/graph/visualization`
- `POST /api/v1/graph/subgraph/query`
- `POST /api/v1/graph/traversal/query`
- `POST /api/v1/graph/paths/query`

The legacy `/api/v1/requirements/graph/**` routes are requirement-only
compatibility routes backed by `GraphClient`. They must not become the
extension point for mixed-entity traversal.

JPA aggregates and link entities remain the source of truth. Apache AGE is a
materialized query layer behind `AgeGraphService`, with an AGE-disabled
fallback that returns the same `GraphProjection` shape. Controllers and domain
services do not assemble SQL, Cypher, labels, relationship types, or property
maps directly for AGE.

## Incumbents To Reuse

- Project scope: `ProjectService.requireProjectId`, repository
  `existsByIdAndProjectId` / `findByIdAndProjectId` methods, and same-project
  checks in owning services.
- Graph target validation: `GraphTargetResolverService` owns the
  internal-versus-external split for link targets.
- Graph projection: add or adjust a `GraphProjectionContributor` for an entity
  family rather than adding entity-specific graph endpoints.
- AGE adapter boundary: `AgeGraphService` owns graph-name validation,
  parameter binding, `APPROVED_PROPERTY_KEYS`, AGE fallback behavior, and
  adapter-side traversal caps.
- API validation: request records with Bean Validation plus service-level
  defensive validation in `MixedGraphService`.
- Error contract: `GroundControlException` subclasses, `GlobalExceptionHandler`,
  and `ErrorResponse`.
- Frontend graph UI: `frontend/src/pages/graph.tsx`,
  `frontend/src/lib/graph-constants.ts`, and the `GraphEntityType` mirror in
  `frontend/src/types/api.ts`.

## Cross-Cutting Layers

- Auth: `/api/v1/graph/**` goes through the shared `ApiPathMatrix` as an
  authenticated user route. `/api/v1/admin/graph/materialize` remains under
  `/api/v1/admin/**` and requires `ROLE_ADMIN`. Do not add controller-local role
  checks or a graph-specific auth layer.
- Browser/session security: mutating graph requests use the existing SPA
  `apiFetch` CSRF path. Do not add an unauthenticated graph export or query
  shortcut.
- Project isolation: every visualization, traversal, subgraph, and path query
  resolves exactly one project before reading a projection. Caller-supplied
  node IDs are valid only after that project projection is built.
- Input bounds: root node count, node identifier length, entity-type filter
  length, max depth, projection node count, projection edge count, and path
  result count stay centralized in `GraphTraversalLimits`.
- AGE injection and cost safety: entity-type filters and project identifiers
  are parameter-bound values. Labels, relationship names, graph names, and
  property keys come only from enums, constants, or the AGE allowlist. Path
  expansion must always have an explicit depth cap, and any result limit must
  be applied inside the Cypher block.
- Error envelopes: validation, not-found, auth, and conflict failures return
  `ErrorResponse`. Do not leak SQL, Cypher, enum class names, request payloads,
  tokens, document bodies, evidence text, stack traces, or property maps.
- Logging and audit context: keep `RequestLoggingFilter`, `ActorFilter`,
  `ActorHolder`, and SLF4J as the logging/audit path. Log low-cardinality
  counts and lifecycle events only.

## Extensibility

The primary extension seam is the query contract, not a new graph subsystem.
If the next change needs directionality, edge-type filters, relationship
families, result ordering, or all-path enumeration, add bounded request fields
to `GraphNeighborhoodQueryRequest` / `GraphPathsQueryRequest`, validate them
with `GraphTraversalLimits`, thread them through `MixedGraphService`, and make
`MixedGraphClient` honor them before projection caps.

Use graph node IDs of the form `GraphEntityType:UUID` for mixed graph roots and
paths. Do not reintroduce requirement UIDs or bare UUIDs as the canonical
mixed-graph identity.

External artifacts stay external identifiers until the backend owns a real
aggregate. Promoting an artifact to first-class graph participation requires
updating its target enum, `GraphEntityType`, resolver case, projection
contributor, AGE property-key registry, frontend enum/color mirrors, MCP mirror
if exposed, and focused tests together.

## Gotchas And Anti-Patterns

- Do not create a universal `graph_edge` table or generic `GraphParticipant`
  superclass to normalize unrelated aggregates.
- Do not extend `GraphClient` or `/requirements/graph/**` for mixed-entity
  queries.
- Do not duplicate graph validation in controllers while leaving
  `MixedGraphService` or `AgeGraphService` unbounded.
- Do not make `entityTypes` a cosmetic post-filter. It must prune both nodes
  and edges before caps are enforced.
- Do not let frontend `GraphEntityType` / color mirrors drift from the backend
  enum when graph types become public API values.
- Do not put large text, secrets, raw evidence bodies, document bodies, or
  high-cardinality metadata into graph-wide node properties without a separate
  security and performance decision.

## Non-Goals

- No implementation of GC-G008 in this note.
- No new domain aggregate, migration, repository, controller, AGE query, MCP
  tool, or frontend feature.
- No replacement for entity-specific services, repositories, lifecycles, or
  link substrates.
- No new workflow automation or privileged GitHub side-effect path.
