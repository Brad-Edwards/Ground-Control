# ADR-046: Partial knowledge and unknown dependency support

- Status: Accepted
- Date: 2026-05-18
- Driver: GC-M018 (issue #726)

## Context

GC-M018 requires the asset model to support incomplete asset knowledge -
manually asserted assets, unknown or tentative dependencies, unclassified
assets, and confidence-marked topology gaps - without forcing false
precision. Risk, threat, and control workflows have to be able to
distinguish confirmed model facts from missing or provisional coverage.

The existing aggregate already carries identity (`OperationalAsset`),
classification (`AssetType` + GC-M011 `subtype` + subtype `metadata` bag),
topology (`AssetRelation`), source/external identity (`AssetExternalId`),
provenance (`AssetRelation.sourceSystem` / `externalSourceId` /
`collectedAt` / free-text `confidence`), cross-entity links (`AssetLink`),
and assessment uncertainty (`RiskAssessmentResult.confidence`,
`uncertaintyMetadata`). None of those answer the question the requirement
asks. The architecture preflight for GC-M018
(`architecture/notes/partial-knowledge-unknown-dependency-preflight.md`) is
explicit about why none of the existing knobs are interchangeable with the
new concept:

- `AssetType.OTHER` means "kind unknown / not in the catalog" - it does
  NOT mean the asset itself is provisional or a placeholder.
- `subtype = null` means "no narrower subtype declared" - not "unknown."
- `metadata = null` / `scopeDesignation = null` mean "not designated,"
  which is again a different question.
- The free-text `confidence` on `AssetRelation` / `AssetExternalId` /
  `Observation` / `EvidenceArtifact` / `RiskAssessmentResult` carries
  provenance - the source system's confidence in its own assertion - and
  has no shared vocabulary across these surfaces. It does NOT answer
  "have *we* (the modeling team) treated this assertion as a confirmed
  model fact?"

The preflight also constrains the shape of the answer: a low-cardinality,
queryable dimension on the canonical asset and topology surfaces, plus an
explicit unresolved-target seam for unknown dependencies; no second
inventory aggregate, no CMDB importer, no scanner, no risk-scoring engine.

## Decision

### 1. `KnowledgeState` enum on asset and relation

Introduce a new L0 pure-value enum `KnowledgeState` in
`backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/`:

- `CONFIRMED` - the assertion is treated as fact. Default.
- `PROVISIONAL` - the assertion was made (manual entry, partial scan,
  low-confidence inference) but has not been validated.
- `UNKNOWN` - explicit placeholder. The team has admitted "we don't
  know." UNKNOWN is a first-class state, not a NULL-vs-something
  ambiguity.

Add the enum as a `NOT NULL` column on both `OperationalAsset` and
`AssetRelation`. DB default `'CONFIRMED'` so legacy rows back-fill; entity
initializer sets `CONFIRMED` for freshly constructed objects pre-persist.
Migrations land as V092 (parent) and V093 (Envers audit-table parity),
following the GC-M012 V069/V070 pattern.

`KnowledgeState.atLeast(other)` is declared on the enum with an explicit
numeric `strength` field (not via `Enum.ordinal()` - errorprone's
`EnumOrdinal` flags ordinal-based comparison because a future reorder
would silently flip the ordering). The comparator supports "minimum
certainty" filters of the form "show me everything CONFIRMED or better"
without forcing every consumer to enumerate the set.

### 2. Wire surface

`KnowledgeState` rides on the existing asset and relation surfaces:

- `AssetRequest` / `UpdateAssetRequest` / `AssetResponse` add
  `knowledgeState` (optional on create - defaults to `CONFIRMED`; null on
  update = leave unchanged). No clear flag because the column is NOT NULL.
- `AssetRelationRequest` / `UpdateAssetRelationRequest` /
  `AssetRelationResponse` add `knowledgeState` with the same semantics.
- `CreateAssetCommand` / `UpdateAssetCommand` /
  `CreateAssetRelationCommand` / `UpdateAssetRelationCommand` add the
  same field. Convenience constructors keep legacy call sites compiling.
- `GET /api/v1/assets` accepts an optional `?knowledgeState=` query
  parameter alongside the GC-M012 / GC-M011 filters.
- `OperationalAssetRepository.findByProjectIdAndArchivedAtIsNullAndFilters`
  adds a `:knowledgeState` predicate (IS NULL guard); a small-cardinality
  index `idx_asset_knowledge_state` (mirrors `idx_asset_criticality` from
  V069) backs the filter.

### 3. Graph projection

`AssetGraphProjectionContributor` emits `knowledgeState` as a property on
asset nodes AND on `AssetRelation` edges. Risk / threat / control
workflows that read the graph see the dimension directly - no second
persisted aggregate, no separate query path.

### 4. Unresolved-dependency seam: the placeholder asset

An unknown dependency is modeled as an `AssetRelation` whose `target` is
an `OperationalAsset` row with `knowledgeState = UNKNOWN` (a placeholder
asset). The relation may itself be `PROVISIONAL` or `UNKNOWN`. This:

- Keeps the existing JPA shape (`AssetRelation.target` is `optional =
  false` - both endpoints are real `OperationalAsset` rows).
- Preserves project scope, audit history, UID uniqueness, graph identity,
  and every other invariant the asset aggregate carries.
- Makes the placeholder visible in every existing read surface (list,
  graph, links) so risk / threat / control consumers don't need a second
  code path.

### 5. MCP and frontend mirrors

- `mcp/ground-control/lib.js` exports `KNOWLEDGE_STATES =
  ["CONFIRMED","PROVISIONAL","UNKNOWN"]` and pins `knowledge_state →
  knowledgeState` in the snake/camel TO_CAMEL map. The `gc_asset` tool
  threads `knowledge_state` through `ASSET_FIELDS` and `RELATION_FIELDS`
  so create / update / relation_create paths forward it.
- `frontend/src/types/api.ts` exports `KnowledgeState` and
  `KNOWLEDGE_STATES`. `frontend/src/pages/graph.tsx` exposes
  `knowledgeState` as a node property row on `OPERATIONAL_ASSET`
  details.

## Alternatives considered

### Make `AssetRelation.target` nullable + add an `unresolvedTargetDescriptor` string

Rejected. It would let an `AssetRelation` row exist without a real target,
which breaks every existing query that joins through the target, the
`@ManyToOne(optional = false)` invariant, the graph projection, and the
audit shadow. The implementation cost is high and the gain is
non-existent - a placeholder `OperationalAsset` with
`knowledgeState=UNKNOWN` covers the same semantic with no schema risk.

### A separate `UnknownDependency` aggregate

Rejected by the preflight. "No second inventory, generic graph-node
table, CMDB adapter, or workflow-specific asset model for partial
knowledge." A separate aggregate would duplicate project scoping, audit,
graph projection, and link semantics with nothing to show for it.

### Per-feature confidence enums (`AssetConfidence`, `TopologyConfidence`, ...)

Rejected. The preflight forbids it: "If confidence becomes
machine-comparable, use one canonical vocabulary and one
normalization/validation path." `KnowledgeState` is that single
vocabulary for the modeling team's confidence in the assertion itself;
the existing free-text `confidence` fields stay where they are for
source-system provenance, on the surfaces where they already exist.
The two are independent - a CONFIRMED edge can carry a confidence
annotation, and an UNKNOWN edge can omit confidence entirely.

### Store knowledge state inside `subtype` `metadata` or in a JSON column

Rejected. The preflight is explicit: subtype `metadata` is for
subtype-specific fields validated by `AssetSubtypeValidator`, not for
model completeness. Storing the state in JSON also defeats indexing,
filterability, and audit-table parity.

## Consequences

- One canonical L0 enum. Same vocabulary on asset, on relation, in MCP,
  in the frontend, in the graph projection. Adding a fourth state would
  need an explicit follow-up requirement.
- Risk, threat, and control workflows that consume `AssetResponse`,
  `AssetRelationResponse`, or the graph reads see `knowledgeState`
  immediately - no per-workflow refactor required for the
  "distinguishability" clause.
- Legacy rows back-fill to `CONFIRMED`, which is the conservative
  default (an existing asset is treated as a real model fact). Callers
  who want to mark legacy rows provisional do so through the existing
  update path.
- Migration version contention with concurrent in-flight PRs is the
  usual risk: V092 / V093 must be bumped if another migration lands
  first. `MigrationSmokeTest` and `RequirementsE2EIntegrationTest`
  hardcode the version list and will fail loudly if drift occurs.
- There is intentionally no automatic promotion workflow (UNKNOWN →
  PROVISIONAL → CONFIRMED is a user-driven update). If a future
  requirement adds lifecycle automation, it lives behind its own ADR.

## Non-goals

- No new CMDB importer, scanner, scheduler, or reconciliation worker.
- No filter knob on risk / threat / control list endpoints in this
  iteration - they read `knowledgeState` from the asset surface they
  already consume. If a future requirement needs per-workflow filtering,
  it lands behind that requirement, not GC-M018.
- No replacement of `OperationalAsset`, `AssetRelation`, `Observation`,
  `AssetExternalId`, `AssetLink`, risk / threat / control links, or
  graph contributors.
- No change to ADR-026 / ADR-037 security, actor provenance, or the
  shared `ErrorResponse` envelope.

## Traceability

- Requirement: GC-M018 (Wave 4).
- Preflight:
  `architecture/notes/partial-knowledge-unknown-dependency-preflight.md`.
- Migrations: V092, V093.
- Enum: `domain/assets/state/KnowledgeState.java`.
- API: `AssetRequest`, `UpdateAssetRequest`, `AssetResponse`,
  `AssetRelationRequest`, `UpdateAssetRelationRequest`,
  `AssetRelationResponse`.
- Service:
  `domain/assets/service/AssetService.java` and the four `*Command`
  records.
- Repository: `OperationalAssetRepository`
  (`findByProjectIdAndArchivedAtIsNullAndFilters` knowledgeState facet).
- Graph: `AssetGraphProjectionContributor`.
- MCP: `mcp/ground-control/lib.js` (`KNOWLEDGE_STATES`,
  TO_CAMEL `knowledge_state`); `mcp/ground-control/index.js`
  (`gc_asset` zod field + ASSET_FIELDS + RELATION_FIELDS).
- Frontend: `frontend/src/types/api.ts`, `frontend/src/pages/graph.tsx`.
