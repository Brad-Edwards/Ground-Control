# Mixed Entity Graph Participation Preflight

Issue: #727
Requirement: GC-G007

This note records architecture guardrails for first-class graph participation
across non-requirement entities. It is not an implementation plan.

## Boundary

The mixed graph is a projection over owned domain aggregates, not a separate
aggregate, workflow engine, or universal link table. JPA entities and their
repositories remain the source of truth. Apache AGE remains an optional,
materialized query layer behind `AgeGraphService`; the in-memory fallback must
produce the same `GraphProjection` contract when AGE is disabled.

A first-class internal graph participant has these properties:

- A project-scoped domain aggregate or link entity owns the persisted fact.
- `GraphEntityType` names the participant type.
- `GraphIds.nodeId(entityType, domainId)` creates the stable graph node ID.
- A `GraphProjectionContributor` emits nodes and internal edges for the
  project.
- `GraphTargetResolverService` validates links to that participant through
  `targetEntityId` and project-scoped repository lookups.
- Public responses may expose the graph node ID for navigation, but REST DTOs
  do not become graph persistence objects.

External artifacts do not become internal graph nodes just because they are
linkable. They stay in `targetIdentifier`, `TraceabilityLink`, or
`EvidenceSourceRef` style references until the backend owns a real aggregate
for them. When a currently external target graduates to a first-class
aggregate, update its target enum, resolver case, projection contributor, API
DTOs, MCP mirror if exposed, and tests together.

`Document` is a project-scoped aggregate today, but it is not in
`GraphEntityType`. GC-G007 work that makes documents graph-native must add a
document projection through the same graph seams. Do not model documents as
requirements, evidence artifacts, or generic external references once the
document aggregate is the intended participant.

## Incumbents To Reuse

- Graph contract: `GraphEntityType`, `GraphIds`, `GraphNode`, `GraphEdge`,
  `GraphProjection`, `GraphProjectionContributor`,
  `GraphProjectionRegistryService`, `MixedGraphService`, `MixedGraphClient`,
  and `GraphTraversalLimits`.
- Graph target validation: `GraphTargetResolverService` owns the
  internal-versus-external target split and project-scoped existence checks.
- AGE boundary: `AgeGraphService` owns SQL and Cypher construction, property
  key allowlisting through `APPROVED_PROPERTY_KEYS`, graph-name validation,
  parameter binding, materialization, and AGE-disabled fallback behavior.
- Link substrates: `AssetLink`, `RiskScenarioLink`, `ControlLink`,
  `FindingLink`, `AuditLink`, `EvidenceSourceRef`, and requirement
  `TraceabilityLink` already carry typed relationship semantics. Extend them
  only when the owning aggregate's language matches the relation.
- Project boundary: `ProjectService.requireProjectId` or
  `resolveProjectId`, repository `existsByIdAndProjectId` /
  `findByIdAndProjectId` methods, and same-project service checks.
- API validation: request records with Bean Validation, Jackson enum binding,
  and service-level `DomainValidationException` for semantic validation.
- Error contract: `GroundControlException` subclasses,
  `GlobalExceptionHandler`, and `ErrorResponse`.
- Audit and observability: Envers on graph-affecting entities, `ActorFilter`,
  `ActorHolder`, `RequestLoggingFilter`, MDC `actor_id` / `request_id`, and
  SLF4J lifecycle logs.
- Tests: contributor unit tests, `MixedGraphServiceTest`,
  `GraphTargetResolverServiceTest`, `AgeGraphServiceTest`,
  `GraphControllerTest`, ArchUnit boundary tests, and `@WebMvcTest`
  controller slices.

## Cross-Cutting Layers

- Auth surface: graph read endpoints live under `/api/v1/**` and pass through
  the shared bearer and browser path matrix. Admin materialization remains
  `/api/v1/admin/graph/materialize` and requires `ROLE_ADMIN`.
- Project scope: every graph query resolves one project before building or
  traversing a projection. Object IDs from request bodies are meaningful only
  after project membership is proven.
- Input validation: DTO Bean Validation caps root IDs, node IDs, entity-type
  filters, and depth. `MixedGraphService` repeats these checks for internal
  callers, then maps unknown entity types to `DomainValidationException`.
- Link target validation: internal targets require `targetEntityId`; external
  or unmodeled targets require `targetIdentifier`. Do not accept both as
  competing identities for the same edge.
- AGE safety: no controller, service, MCP tool, or contributor assembles SQL,
  Cypher, labels, relationship types, or AGE property maps directly. Dynamic
  values reach AGE through adapter-owned binding or explicit allowlists.
- Projection size and traversal cost: apply `GraphTraversalLimits` at DTO,
  domain-service, and adapter layers. Filtering by `entityTypes` must filter
  both nodes and edges before projection caps are enforced.
- Error envelopes: validation, not-found, auth, and conflict failures return
  the standard `ErrorResponse` shape. Do not add graph-local error wrappers or
  leak enum class names, SQL, Cypher, request bodies, tokens, or stack traces.
- Config and secrets: GC-G007 does not require new env vars, token arguments,
  subprocesses, shell commands, or argv-visible secrets. AGE configuration stays
  in `AgeProperties`.
- Runtime exposure: graph work must not introduce user-supplied Cypher,
  arbitrary filesystem scans, network calls, or direct GitHub side effects.
- Logging: log low-cardinality lifecycle events and counts. Do not log graph
  property maps, evidence text, document bodies, bearer tokens, or full request
  payloads.

## Extensibility

The extension seam is a graph participant inventory implemented by the existing
types: target enum value, `GraphEntityType`, resolver case, projection
contributor, AGE property-key registry entry, REST response `graphNodeId` when
useful, MCP enum mirror when exposed, and focused tests. The next participant
should require adding one bounded inventory slice, not a new graph subsystem.

Property maps are an API and AGE schema surface. Prefer stable, low-cardinality
fields that support filtering, labeling, and traversal context. Keep large
free text, opaque metadata, secrets, questionnaire answers, document bodies,
and high-cardinality maps out of graph-wide projection unless a separate
security and performance decision approves them.

Relation semantics belong with edge types, not ad hoc properties. If a
relationship affects traversal, impact, coverage, audit history, or lifecycle,
name it as a typed relation in the owning domain and project it as an edge.

## Gotchas And Anti-Patterns

- Do not create a generic `GraphParticipant` superclass or universal
  `graph_edge` table just to make entity lists look uniform.
- Do not duplicate link schemas per entity when the existing target
  resolver and link services already cover the internal/external split.
- Do not keep first-class internal targets as raw string identifiers after
  their aggregate exists.
- Do not add a graph-only controller, graph-only service, or graph-only
  exception hierarchy for one entity family.
- Do not fold risks, controls, findings, audits, evidence, documents, issues,
  and external artifacts into `Requirement`, `TraceabilityLink`, or
  `EvidenceArtifact` for convenience.
- Do not project edges whose endpoint nodes are filtered out or archived by the
  relevant contributor's node contract.
- Do not add AGE property keys without updating the adapter allowlist and
  regression tests.
- Do not make `entityTypes` filtering cosmetic. It must filter edges by visible
  endpoints as well as nodes.
- Do not let frontend, MCP, and backend enum mirrors drift when graph entity or
  target enums become public API values.

## Non-Goals

- No implementation of GC-G007 in this preflight.
- No new domain aggregate, migration, controller, repository, AGE query, or UI
  graph feature.
- No replacement for existing entity-specific services, repositories, link
  entities, or lifecycle models.
- No new workflow automation, issue-thread behavior, GitHub sync behavior, or
  privileged side-effect path.
- No guarantee that every noun in GC-G007 already has a backend aggregate. The
  graph can only make existing or newly modeled aggregates first-class; missing
  aggregates need their own domain decision before graph projection.
