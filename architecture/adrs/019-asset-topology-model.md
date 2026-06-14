# ADR-019: Asset Topology and Boundary Relationships

## Status

Accepted

## Date

2026-03-31

## Context

Ground Control needs to model operational assets and the typed relationships among them so that multi-hop impact, threat, and control analysis can traverse the system model. Prior art from service models, cloud inventories, and attack-path products shows that dependency, communication, and boundary relationships are the substrate for meaningful analysis. Hiding topology in per-type embedded fields makes asset graphs brittle and prevents graph-based traversal.

The system must:

- Model operational assets with project scoping, human-readable UIDs, and soft-delete
- Support typed relationships: containment, dependency, communication, trust boundary, service-support, identity-to-resource access, and data-flow/integration
- Store topology as explicit graph relations (junction entity) rather than embedded custom fields
- Support multi-hop traversal for impact analysis, cycle detection, and subgraph extraction

Key constraints:

- The asset graph is **not** constrained to be a DAG - some relationship types (for example, COMMUNICATES_WITH) legitimately form cycles
- The existing `GraphAlgorithms` utility (cycle detection, BFS reachability, topological sort) is pure and domain-agnostic
- Apache AGE is available but not required for initial implementation

## Decision

### 1. Domain Structure

The assets domain follows the established pattern from ADR-011:

```
domain/assets/
    model/           # JPA entities: OperationalAsset, AssetRelation
    state/           # Enums: AssetType, AssetRelationType
    service/         # AssetService (write-owner), AssetTopologyService (read-only analysis)
    repository/      # Spring Data JPA interfaces
```

### 2. OperationalAsset Entity

Extends `BaseEntity` (UUID PK, createdAt, updatedAt). Includes project scoping with `@NotAudited` on the Project ManyToOne reference. Supports soft-delete via `archivedAt`. Asset types: APPLICATION, SERVICE, DATABASE, NETWORK, HOST, CONTAINER, IDENTITY, DATA_STORE, BOUNDARY, OTHER.

### 3. AssetRelation Association Entity

`AssetRelation` is a first-class association entity extending `BaseEntity` rather than a bare junction row. It models a directed edge from `source` to `target`, typed via `AssetRelationType`, with a unique constraint on `(source_id, target_id, relation_type)`. In addition to the edge identity, it carries mutable metadata such as `description`, `sourceSystem`, `externalSourceId`, `collectedAt`, and `confidence`, plus standard lifecycle timestamps (`createdAt`, `updatedAt`). JML contracts still prevent self-relations. CASCADE delete removes relations when assets are deleted.

### 4. Seven Relationship Types

Each type maps to a requirement clause from GC-M013:

| Type | Meaning | Example |
|------|---------|---------|
| CONTAINS | Containment/composition | VPC contains Subnet |
| DEPENDS_ON | Runtime dependency | App depends on Database |
| COMMUNICATES_WITH | Network/API communication | Frontend communicates with API |
| TRUST_BOUNDARY | Crosses a trust boundary | DMZ to Internal crossing |
| SUPPORTS | Service-support relationship | Load balancer supports App |
| ACCESSES | Identity-to-resource access | ServiceAccount accesses S3 Bucket |
| DATA_FLOW | Data-flow or integration | ETL pipeline data flow |

### 5. Graph Analysis via Existing Algorithms

The `AssetTopologyService` reuses `GraphAlgorithms` from the requirements domain (pure utility, no Spring dependencies). This avoids code duplication while the class lives in `domain.requirements.service`. No ArchUnit rule prevents cross-domain imports within the domain layer.

Analysis capabilities: cycle detection (all relation types), impact analysis (reverse BFS reachability), subgraph extraction (bidirectional BFS from roots).

### 6. No Write-Time Cycle Enforcement

Unlike requirement relations (which enforce DAG structure for certain types), asset relations do not enforce acyclicity at write time. Some relation types legitimately form cycles. Cycle detection is an analysis tool, not a write-time constraint.

## Consequences

**Positive:**
- Enables multi-hop impact, threat, and control analysis across the asset model
- Reuses proven graph algorithms without code duplication
- Provides stable identity and lifecycle metadata for topology relationships from external inventories and sync jobs
- Full audit trail via Hibernate Envers

**Negative:**
- Relational storage limits practical graph size to thousands of nodes (sufficient for operational asset inventories)
- No native graph query language (Cypher) for ad-hoc traversal - AGE integration can be added later

**Risks:**
- Cross-entity-type relations (asset-to-requirement) may be needed for unified system model traversal - can be addressed via TraceabilityLink or a new linking mechanism
- If GraphAlgorithms evolves to become requirements-specific, the assets domain would need its own copy or the utility should be extracted to a shared package
