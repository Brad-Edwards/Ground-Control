# Architecture Model Aggregate Preflight

Issue: #1118
Requirement: GC-GRC-005

This note records architecture guardrails for the graph-native, server-side
architecture-model aggregate. It is not an implementation plan.

## Boundary

GC-GRC-005 promotes the derived DFD model from adapter output into a
first-class Ground Control aggregate. The aggregate is the authoritative
server-side system model for GRC work: components/processes, data stores,
external entities, data flows, trust boundaries, and data classifications as
versioned snapshots with diffing.

Keep these concepts separate:

- `SystemModelFact` is the adapter output substrate. It is an input to the
  architecture model, not the architecture-model aggregate.
- `BoundaryModelSnapshot` is the canonical boundary coverage output for
  GC-GRC-004. It contributes trust-boundary context, but it is not the full DFD
  model and must not become the component/flow/data-classification store.
- `OperationalAsset` and `AssetRelation` model operational topology. They can
  link to architecture-model elements, but they are not substitutes for DFD
  components, stores, external entities, boundaries, or flows.
- Architecture artifacts in tracked repo files remain external artifacts per
  `architecture/notes/architecture-model-artifacts.md` unless and until their
  content is imported into this server-side aggregate. Repo files are inputs or
  mirrors only; Ground Control's database is authoritative.
- `ThreatModelLinkTargetType.ARCHITECTURE_MODEL` must graduate from an
  external `targetIdentifier` path to a project-scoped internal `targetEntityId`
  path that resolves through `GraphTargetResolverService`.

The stable graph target should be an architecture-model element identity, not a
snapshot id. Snapshots carry versioned state for those elements. Diffing should
compare snapshots by stable element keys/identities within a project; Envers is
audit history, not the model-versioning or diffing mechanism.

Data flows have two roles: they are DFD elements that need provenance, version
state, and linkability, and they also create traversal relationships between
source and target elements. Do not persist a separate flow node and unrelated
edge record that can drift. Persist one flow element and project traversal
edges from that source of truth.

## Incumbents To Reuse

- Derivation substrate: `DerivationService`, `DerivationAdapterRegistry`,
  `DerivationAdapter`, `DerivationRun`, `SystemModelFact`,
  `DerivedSystemModelFact`, `DerivationFactProvenance`, and the existing
  sensitive-payload rejection in `DerivationService`.
- Boundary substrate: `BoundaryModelService`, `BoundaryModelSnapshot`,
  `BoundaryModelBoundary`, assignments, and gap records.
- Aggregate shape: project-scoped `Service+Aggregate`, command DTOs,
  repositories with `projectId` predicates, `BaseEntity`, Flyway, and Envers.
- REST shape: `ProjectService`, request records with Bean Validation,
  controller-to-service routing, and `@WebMvcTest` controller slices.
- Error shape: `DomainValidationException`, `NotFoundException`,
  `ConflictException`, `GlobalExceptionHandler`, and `ErrorResponse`.
- Audit and logging: `ActorFilter`, `ActorHolder`, Envers, and SLF4J
  low-cardinality lifecycle logs.
- Graph shape: `GraphEntityType`, `GraphIds`, `GraphNode`, `GraphEdge`,
  `GraphProjectionContributor`, `GraphProjectionRegistryService`,
  `MixedGraphService`, `GraphTraversalLimits`, and AGE property-key allowlisting
  in `AgeGraphService`.
- Link validation: `GraphTargetResolverService` and the existing
  internal-target versus external-target split.
- MCP shape: ADR-035 action-discriminated tools, `pick`, `reqArg`,
  `toCamelCase`, `TO_CAMEL`, `RequestError`, `addAuthorizationHeader`,
  shared `link-create.js` semantics where links are involved, and the
  OpenAPI/MCP contract tests from ADR-034.

## Cross-Cutting Layers

- Auth surface: REST endpoints remain under `/api/v1/**` and pass through the
  shared bearer/browser `ApiPathMatrix`. Do not add controller-local auth
  checks. If any endpoint exposes cross-project rollups or operator-only
  mutation, make that an explicit `ApiPathMatrix` rule with tests.
- Project scope: every read, write, diff, snapshot lookup, graph projection, and
  link-target resolution must resolve exactly one project through
  `ProjectService` and repository predicates. Element keys are not globally
  unique without project and snapshot/current-state context.
- Input validation: DTO Bean Validation should bound request shapes; service
  validation should own semantic rules such as snapshot immutability,
  stable-key uniqueness, valid element kinds, flow endpoints in the same
  snapshot/project, classification-key format, and provenance completeness.
- Secret handling: architecture detail is sensitive topology. Store model facts
  needed for DFD reasoning, not source bodies, raw diffs, raw command output,
  environment values, bearer tokens, provider credentials, or secret values.
  Reuse the derivation blocked-key discipline for any adapter-produced or
  manual payload-style fields.
- Error envelopes: validation, conflict, not-found, auth, and data-integrity
  failures must flow through `GroundControlException` subclasses and
  `GlobalExceptionHandler`. Error bodies must not reflect raw model payloads,
  source snippets, tokens, SQL/Cypher, stack traces, or full request bodies.
- Graph/AGE exposure: controllers, services, MCP tools, and contributors do not
  assemble SQL or Cypher. New graph properties must be stable, low-cardinality
  fields and must be registered in `AgeGraphService.APPROVED_PROPERTY_KEYS`
  with regression coverage.
- MCP exposure: writes should use a named action-discriminated adapter with
  explicit body allowlists. Reads may use `gc_query` only after adding a bounded
  `/api/v1/architecture-models` allowlist entry; large snapshot payloads need
  paginated or summary reads so the 1 MiB `gc_query` cap is not the primary
  protection. MCP must not create a second persistence path or duplicate backend
  target-validation logic.
- Configuration: add `@ConfigurationProperties` only for real operator knobs
  such as derivation adapter enablement, rule versions, or bounded retention.
  Do not add ad hoc env parsing or `@Value` fields.
- OS/runtime exposure: this aggregate does not require new shell commands,
  GitHub side effects, arbitrary filesystem scans, or user-supplied subprocess
  arguments. If a future adapter invokes a tool, use existing bounded
  ProcessBuilder-style patterns with fixed working directory, timeouts, output
  caps, sanitized errors, and no secret-rich argv/env.

## Extensibility

The immediate next changes are GC-GRC-006 data-classification lattice,
additional derivation adapters, threat/control enumeration over model elements,
drift comparison, and code-keyed reverse lookup.

Keep these seams explicit:

- DFD element kind, not one Java class or graph type per current DFD noun unless
  a concrete traversal or lifecycle need proves otherwise.
- Stable element identity plus versioned snapshot state.
- Snapshot schema version and model version.
- Diff mode/reason codes such as added, removed, changed, unchanged, stale, and
  provenance-only change.
- Provenance source type (`adapter`, `declaration`, future import), source
  fact/declaration key, derivation run, ruleset/tool version, and commit SHA.
- Data-classification key/reference that can later point at the project-scoped
  lattice instead of becoming a hardcoded enum.
- Flow endpoint roles and directionality.
- Graph projection property inventory and frontend/MCP enum mirrors if public
  graph values change.

Prefer one `ARCHITECTURE_MODEL_ELEMENT` style graph participant with an
element-kind property unless reviewers identify a concrete need for separate
top-level graph entity types. This keeps the `ARCHITECTURE_MODEL` link target
singular and avoids enum churn across backend, MCP, frontend, AGE, and tests.

## Gotchas And Anti-Patterns

- Do not use `SystemModelFact.payload` as the persisted architecture model.
- Do not make `BoundaryModelSnapshot` the DFD aggregate by adding unrelated
  component/store/flow fields to it.
- Do not store the authoritative model in analyzed repository files or require
  agents to read repo files for DFD context.
- Do not leave `ARCHITECTURE_MODEL` threat-model links as raw identifiers after
  real model elements exist.
- Do not link GRC entities to snapshot rows when the intent is a durable element
  identity across versions.
- Do not treat Envers audit rows as versioned snapshots or model diffs.
- Do not represent data flows only as graph edges if they must be linkable
  elements with provenance and lifecycle.
- Do not introduce a generic graph participant superclass, universal graph-edge
  table, feature-local exception hierarchy, or feature-local auth path.
- Do not put large text, opaque metadata maps, source snippets, evidence bodies,
  or high-cardinality adapter payloads into graph-wide node properties.
- Do not duplicate validation in MCP, frontend, and backend. Backend DTOs and
  services are the semantic authority; mirrors are contract adapters.
- Do not hardcode this repository's `api/domain/infrastructure/mcp/frontend`
  layout as the platform's architecture ontology.

## Non-Goals

- No implementation of GC-GRC-005 behavior in this note.
- No threat enumeration, control mapping, attack-path analysis, risk scoring, or
  screening-record v2 renderer.
- No implementation of the GC-GRC-006 classification lattice beyond preserving
  its future seam.
- No runtime DAST, cloud inventory, provider credential collection, deployment
  health checking, or dynamic instrumentation.
- No replacement for asset topology, boundary modeling, traceability links,
  evidence artifacts, or existing graph traversal endpoints.
- No GitHub issue-thread posting or workflow-record rendering changes.
