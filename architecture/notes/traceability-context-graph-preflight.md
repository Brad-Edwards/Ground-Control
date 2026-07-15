# Traceability Context-Graph Preflight

Issue: #1308
Requirement: none

This note records architecture guardrails for projecting requirement
traceability into the mixed context graph and removing dead node vocabulary. It
is not an implementation plan.

## Boundary And Identity

`TraceabilityLink` remains the relational, Envers-audited source of truth.
`RequirementGraphProjectionContributor` owns the read-only projection of both
requirement relations and requirement-to-artifact traceability; neither
`TraceabilityService` nor any controller may write AGE as a side effect. Snapshot
publication, filtering, and traversal continue through
`GraphProjectionRegistryService`, `AgeGraphService`, and `MixedGraphService`
under ADR-062.

The projection direction is the controlled-vocabulary direction already
declared by ADR-084: requirement source to artifact target. The edge id is the
`TraceabilityLink` UUID and the edge type is the existing `LinkType.name()`.
Do not reverse `IMPLEMENTS`, `TESTS`, `DOCUMENTS`, `CONSTRAINS`, or `VERIFIES`
because their English phrasing might also be read artifact-to-requirement.

Artifact endpoint identity has two cases:

- `ArtifactType.CONTROL` and `ArtifactType.RISK_SCENARIO` resolve by their
  ADR-011 canonical UID within the link's project and land on the existing
  `CONTROL` or live `RISK_SCENARIO` node when that aggregate exists. Do not
  create a second identifier-shaped node for a first-class target.
- Every artifact without a projected aggregate lands on an emitted
  `ARTIFACT_REFERENCE` node. This is a graph classification for an
  identifier-addressed traceability endpoint, not a new JPA aggregate and not
  an `EvidenceArtifact`, `Document`, or `ResearchArtifact` alias.

An artifact-reference identity is the exact persisted tuple `(project id,
ArtifactType, artifactIdentifier)`. It must not trim, case-fold, prefix, or
otherwise reinterpret ADR-011 identifiers during projection. The wire node id
must be deterministic, unambiguous, project-qualified, and no longer than
`GraphTraversalLimits.MAX_NODE_IDENTIFIER_LENGTH`; use a bounded digest of
length-framed tuple components through the existing `GraphIds` identity helper
rather than embedding the up-to-500-character identifier directly. Project
qualification is mandatory because the published AGE snapshot contains all
projects and `AgeGraphService` matches edge endpoints by node id.

Multiple links to the same tuple share one artifact-reference node. Missing or
archived first-class targets must not produce a dangling edge or silently alias
an unrelated aggregate; retain them as identifier-addressed artifact references
so the traceability fact remains traversable and visibly unresolved. Promotion
to a canonical aggregate node on a later snapshot is allowed only when the
project-scoped resolver finds that aggregate.

## Incumbents To Reuse

- `TraceabilityLink`, `ArtifactType`, `LinkType`, and the ADR-011 identifier
  conventions are the schema and semantic authority. Do not add another link
  entity, artifact enum, or edge vocabulary.
- `TraceabilityLinkRepository` owns a project-scoped, join-fetched query for
  links whose source requirement is not archived. Do not fetch every link and
  filter projects in memory, and do not issue one query per requirement.
- `RequirementGraphProjectionContributor` remains the requirements projection
  seam. It must deduplicate emitted artifact-reference nodes and omit links from
  archived requirements so every emitted edge has a projected source.
- `GraphEntityType`, `GraphIds`, `GraphNode`, `GraphEdge`, and
  `GraphProjectionContributor` remain the graph model. `CONTROL_LINK` and
  `AUDIT_LINK` leave `GraphEntityType`; link rows continue to materialize as
  edges from their existing contributors.
- `GraphTargetResolverService` establishes the same-project internal-target
  policy. Projection is read-only and should not call write validation or throw
  because historical target data is unresolved, but its internal-versus-
  identifier distinction must remain consistent with that policy.
- `contracts/ontology/gc-controlled-vocabularies-v1.json` declares the new
  artifact-reference classification and removes the two retired link-node
  classifications. `gc-artifact-bindings-v1.json` binds the enum value and adds
  the requirements contributor's `getLinkType` selector to the existing
  `LinkType` surface. Do not duplicate the five traceability edge definitions.
- `AgeGraphService.APPROVED_PROPERTY_KEYS` remains the AGE property schema and
  parameter binding remains the only path for artifact identifiers. Project
  identifiers, artifact identifiers, titles, URLs, or other row data must never
  become Cypher labels, relationship types, property keys, or SQL text.

## Cross-Cutting Layers

- **Authorization and project scope:** the existing bearer/browser security
  chains, IP allowlist, and `ApiPathMatrix` continue to protect `/api/v1/graph/**`;
  materialization remains `ROLE_ADMIN` under `/api/v1/admin/**`. `ProjectService`
  resolves the requested project before traversal, the repository query scopes
  source rows by that project, artifact-reference ids include project scope,
  and AGE reads require both endpoints to carry that project identifier.
- **Shape and validation:** `@Valid` graph query DTOs and
  `GraphTraversalLimits` keep their root-count, depth, filter-count, node-id
  length, node-count, and edge-count limits. `MixedGraphService` repeats the
  checks for internal callers. Adding artifact nodes increases projection size;
  it does not justify bypassing or silently truncating these caps.
- **Persistence and consistency:** no migration or write path is needed. The
  project-scoped `Repository` query reads existing audited rows, and
  `AgeGraphService` publishes the result inside the ADR-062 repeatable-read,
  versioned-snapshot transaction. Do not introduce per-link AGE updates,
  in-process event synchronization, or a second projection cache.
- **Error handling:** unknown filters, oversized inputs, project failures, and
  projection-cap failures continue through `DomainValidationException` or
  `NotFoundException`, `GlobalExceptionHandler`, and `ErrorResponse`. Do not add
  a graph- or traceability-specific error envelope. Errors must not echo raw
  artifact URLs, titles, source contents, SQL, Cypher, or stack traces.
- **Observability and audit:** materialization keeps the existing bounded SLF4J
  snapshot log (graph name, counts, version) and `ActorHolder` publisher actor.
  Projection is not a mutation of `TraceabilityLink`, so it must not create a
  second audit trail or log each artifact identifier. Envers remains the
  historical authority for the link rows.
- **Secrets, configuration, and OS exposure:** this change needs no new
  `@ConfigurationProperties`, environment variables, secrets, subprocesses,
  filesystem scans, network clients, or argv values. Do not derive artifact
  nodes by reading linked files or contacting GitHub; project only persisted
  identifiers.
- **Contracts and UI mirrors:** the backend enum remains semantic authority.
  Preserve the current JSON strings while typing the graph response DTO fields
  as `GraphEntityType`, so Springdoc and the ADR-082 generator can emit a real
  `GraphEntityType` union plus one iterable `GRAPH_ENTITY_TYPES` constant. Add
  `GraphEntityType` as one row in the existing ADR-034 enum-contract inventory;
  frontend colors and tooltip coverage consume that generated constant instead
  of keeping their own lists. Remove every mirror value absent from the backend,
  including `CONTROL_LINK`, `AUDIT_LINK`, and the already-retired
  `RISK_APPETITE_PROFILE`; add only the emitted `ARTIFACT_REFERENCE` value.
  Update ontology bindings and `ARCHITECTURE.md` in the same change. Generated
  OpenAPI/TypeScript artifacts are refreshed through contract tooling, never
  hand edited.

## Extensibility

The extension seam is the artifact-target mapping keyed by `ArtifactType`.
Adding a future first-class contributor for ADRs, tests, proofs, or repository
artifacts should change that mapping from `ARTIFACT_REFERENCE` to the canonical
project-scoped node after the aggregate can be resolved; it must not change
stored `TraceabilityLink` identifiers or create a parallel link schema. The
bounded `GraphIds` identifier-addressed form is reusable by another contributor
only when it has the same project-qualified identity requirements.

## Verification Guardrails

Contributor unit tests must cover all five `LinkType` values, shared-artifact
deduplication, exact identifier handling, archived requirements, project
separation, first-class control/risk resolution, and unresolved-target fallback.
`MixedGraphService` tests must prove a requirement-to-artifact neighborhood/path
is traversable. A real AGE integration test must materialize and read the edge,
including an adversarial identifier, because a contributor-only assertion
cannot detect endpoint matching or parameter-binding failures. Existing
`@WebMvcTest` controller slices need adjustment only for an observable response
shape; do not add a new controller or `@SpringBootTest` merely for coverage.

The ontology test/gate must prove source and binding parity after the enum and
contributor selector changes. `make policy` and the ontology binding check are
completion gates, not substitutes for behavioral projection tests.

## Gotchas And Anti-Patterns

- Do not model `TraceabilityLink`, `ControlLink`, or `AuditLink` as graph nodes;
  they are edges with stable row ids.
- Do not conflate an artifact reference with `EvidenceArtifact`, `Document`,
  `ResearchArtifact`, `VerificationResult`, or a generic domain aggregate.
- Do not emit one artifact node per link or omit the project from a synthetic
  node id; either choice creates duplicate/cross-project endpoint matches in the
  global snapshot.
- Do not embed a raw 500-character artifact identifier into a caller-supplied
  graph node id; it violates the canonical 256-character traversal boundary.
- Do not normalize artifact identifiers in the projection. ADR-011 already
  defines their type-scoped encodings, and changing identity only on the read
  side would merge or split persisted facts.
- Do not project archived-source edges, leave dangling endpoints, perform N+1
  repository reads, or rely on AGE silently dropping an unmatched edge.
- Do not reuse a similarly named property key or edge term with different
  semantics merely to avoid registering the correct ontology/property shape.
- Do not leave graph response `entityType` fields as unbounded strings and then
  hand-maintain backend enum lists in frontend tests. Reuse the generated
  `GRAPH_ENTITY_TYPES` constant and the existing ADR-034 inventory.
- Do not revive `RISK_APPETITE_PROFILE`: ADR-089 removed its owning composed-GRC
  aggregate. The decision required by ADR-084 is removal, while retained risk,
  control, evidence, finding, asset, threat, and traceability aggregates remain
  unaffected.

## Non-Goals

- No traceability write, validation, lifecycle, sync-status, identifier-format,
  or Envers behavior change.
- No new controller, endpoint, query language, graph store, graph aggregate,
  migration, scheduler, outbox, or event-driven projection path.
- No graph projection for every external target on asset/control/risk/threat/
  finding/audit link surfaces; this issue is the requirement traceability spine.
- No automatic inference of links from repositories, GitHub, documents,
  controls, tests, proofs, or verification results.
- No change to graph authorization, project-selection behavior, traversal caps,
  snapshot retention, or AGE configuration.
