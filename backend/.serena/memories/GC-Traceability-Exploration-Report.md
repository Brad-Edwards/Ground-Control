# Ground Control Traceability Model - Comprehensive Exploration Report

## Overview
Ground Control implements a sophisticated traceability system connecting requirements, assets, and other entities through multiple types of relationships and links. The model supports bidirectional impact analysis across both the requirement dependency graph and asset topology.

---

## 1. TRACEABILITY LINK ENTITY (Requirement-to-Artifact Links)

### TraceabilityLink Model
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/TraceabilityLink.java` (Lines 18-143)

**Purpose:** Links a requirement to an external artifact for traceability

**Key Fields:**
- `requirement` (Requirement): The source requirement (ManyToOne, `FetchType.LAZY`, required)
- `artifactType` (ArtifactType enum): Type of the artifact
- `artifactIdentifier` (String): Unique identifier in external system (max 500 chars)
- `linkType` (LinkType enum): Nature of the relationship
- `syncStatus` (SyncStatus enum): Sync state with external source (default: SYNCED)
- `artifactUrl` (String): URL to the artifact (max 2000 chars, default: "")
- `artifactTitle` (String): Display name for the artifact (max 255 chars, default: "")
- `lastSyncedAt` (Instant): Last synchronization timestamp

**Uniqueness Constraint:**
- Composite unique constraint on: requirement_id, artifact_type, artifact_identifier, link_type

**JPA Annotations:**
- @Audited (Hibernate Envers for audit trail)
- @Entity, @Table, @SuppressWarnings for JML contract annotations

---

### ArtifactType Enum
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/ArtifactType.java`

```
GITHUB_ISSUE
PULL_REQUEST
CODE_FILE
ADR (Architecture Decision Record)
CONFIG
POLICY
TEST
SPEC
PROOF
DOCUMENTATION
RISK_SCENARIO
CONTROL
```

---

### LinkType Enum
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/LinkType.java`

```
IMPLEMENTS - artifact implements the requirement
TESTS     - artifact tests/validates the requirement
DOCUMENTS - artifact documents the requirement
CONSTRAINS - artifact constrains the requirement
VERIFIES  - artifact verifies the requirement
```

---

### SyncStatus Enum
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/SyncStatus.java`

```
SYNCED  - artifact is in sync with Ground Control
STALE   - artifact is out of sync
BROKEN  - link to artifact is broken/invalid
```

---

## 2. TRACEABILITY LINK REPOSITORY

**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/TraceabilityLinkRepository.java`

**Key Query Methods:**
- `findByRequirementId(UUID)` → List<TraceabilityLink>
- `findByRequirementIdIn(Collection<UUID>)` → List<TraceabilityLink> (bulk fetch)
- `findByArtifactType(ArtifactType)` → List<TraceabilityLink>
- `existsByRequirementIdAndArtifactTypeAndArtifactIdentifierAndLinkType(...)` → boolean (uniqueness check)
- `existsByRequirementId(UUID)` → boolean
- `existsByRequirementIdAndLinkType(UUID, LinkType)` → boolean (coverage check)
- `findRequirementIdsWithLinkType(Collection<UUID> requirementIds, LinkType linkType)` → Set<UUID> (custom query for bulk coverage analysis)
- `findByArtifactTypeAndArtifactIdentifierWithRequirement(...)` → List<TraceabilityLink> (JOIN FETCH requirement)

---

## 3. TRACEABILITY SERVICE

**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java` (Lines 15-90)

**Purpose:** Service for managing traceability link lifecycle

**Key Methods:**
1. `createLink(UUID requirementId, CreateTraceabilityLinkCommand cmd)` → TraceabilityLink
   - Validates requirement exists
   - Prevents duplicate links
   - Handles artifact type and link type validation

2. `createLinkUnchecked(Requirement req, ArtifactType type, String identifier, LinkType linkType)` → TraceabilityLink
   - Creates link without existence checks

3. `createLink(Requirement req, ArtifactType type, String identifier, LinkType linkType)` → TraceabilityLink (Overloaded)
   - Full implementation with validation
   - Persists link to database
   - Validates artifact identifier is non-empty

4. `getLinksForRequirement(UUID requirementId)` → List<TraceabilityLink>
   - Retrieves all links for a requirement

5. `deleteLink(UUID linkId)` → void
   - Deletes traceability link by ID

**Fields:**
- `requirementRepository` (RequirementRepository)
- `traceabilityLinkRepository` (TraceabilityLinkRepository)

---

## 4. ASSET-CENTRIC TRACEABILITY (Asset Links)

### AssetLink Model
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetLink.java`

**Purpose:** Links an operational asset to requirements, controls, risk scenarios, findings, or external artifacts

**Key Fields:**
- `asset` (OperationalAsset): Source asset (ManyToOne, required)
- `targetType` (AssetLinkTargetType enum): Type of target entity
- `targetEntityId` (UUID): ID of target in Ground Control (for internal targets)
- `targetIdentifier` (String): External identifier (max 500 chars)
- `linkType` (AssetLinkType enum): Relationship type
- `targetUrl` (String): URL to target (max 2000 chars)
- `targetTitle` (String): Display name (max 255 chars)

**Uniqueness Constraints:**
- Composite on: asset_id, target_type, target_identifier, link_type
- Composite on: asset_id, target_type, target_entity_id, link_type

---

### AssetLinkType Enum
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetLinkType.java`

```
IMPLEMENTS    - asset implements the target
MITIGATES     - asset mitigates the target risk
SUBJECT_OF    - asset is subject of the target
EVIDENCED_BY  - asset evidenced by the target
GOVERNED_BY   - asset governed by the target
DEPENDS_ON    - asset depends on the target
ASSOCIATED    - asset associated with the target
```

---

### AssetLinkTargetType Enum
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetLinkTargetType.java`

```
REQUIREMENT
CONTROL
RISK_SCENARIO
RISK_REGISTER_RECORD
RISK_ASSESSMENT_RESULT
TREATMENT_PLAN
METHODOLOGY_PROFILE
THREAT_MODEL_ENTRY
FINDING
EVIDENCE
AUDIT
EXTERNAL
```

---

## 5. ASSET RELATIONS (Asset-to-Asset Topology)

### AssetRelation Model
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetRelation.java`

**Purpose:** Represents directed, typed relationships between operational assets (for topology/dependency modeling)

**Key Fields:**
- `source` (OperationalAsset): Source asset (ManyToOne, required)
- `target` (OperationalAsset): Target asset (ManyToOne, required)
- `relationType` (AssetRelationType enum): Type of relationship
- `description` (String): Optional description (TEXT column)
- `sourceSystem` (String): External source system (max 100 chars)
- `externalSourceId` (String): External identifier for the relation (max 500 chars)
- `collectedAt` (Instant): When the relation was collected
- `confidence` (String): Confidence/quality metadata (max 50 chars)

**Uniqueness Constraint:**
- Composite on: source_id, target_id, relation_type

**Self-Relation Validation:**
- Constructor throws DomainValidationException if source equals target

---

### AssetRelationType Enum
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetRelationType.java`

```
CONTAINS          - source contains target
DEPENDS_ON        - source depends on target
COMMUNICATES_WITH - source communicates with target
TRUST_BOUNDARY    - source is trust boundary to target
SUPPORTS          - source supports target
ACCESSES          - source accesses target
DATA_FLOW         - data flows from source to target
```

---

## 6. REQUIREMENT RELATIONS (Requirement Dependency Graph)

### RequirementRelation Model
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/RequirementRelation.java`

**Purpose:** Represents directed dependencies/relationships between requirements

**Key Fields:**
- `source` (Requirement): Source requirement (ManyToOne, required)
- `target` (Requirement): Target requirement (ManyToOne, required)
- `relationType` (RelationType enum): Type of relationship
- `description` (String): Optional description (TEXT column)
- `createdAt` (Instant): Creation timestamp

**Uniqueness Constraint:**
- Composite on: source_id, target_id, relation_type

---

### RelationType Enum
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/RelationType.java`

```
PARENT        - source is parent of target (hierarchy)
DEPENDS_ON    - source depends on target
CONFLICTS_WITH - source conflicts with target
REFINES       - source refines target (decomposition)
SUPERSEDES    - source supersedes target
RELATED       - source related to target
```

**DAG Types (used for impact analysis):**
- `RelationType.PARENT`
- `RelationType.DEPENDS_ON`
- `RelationType.REFINES`

---

## 7. IMPACT ANALYSIS FUNCTIONALITY

### AnalysisService - Requirement Impact Analysis
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (Lines 31-518)

**Key Method: `impactAnalysis(UUID requirementId)` (Lines 103-132)**

```java
public Set<Requirement> impactAnalysis(UUID requirementId) {
    // 1. Find requirement and verify it exists
    Requirement seed = requirementRepository.findById(requirementId)
        .orElseThrow(() -> new NotFoundException(...));

    // 2. Get all DAG-type relations for the project
    UUID projectId = seed.getProject().getId();
    List<RequirementRelation> relations = relationRepository
        .findActiveByProjectAndRelationTypeIn(projectId, DAG_TYPES);

    // 3. Build reverse adjacency list (downstream = those that depend on target)
    // target -> list of sources (who depends on this target)
    Map<UUID, List<UUID>> reverseAdj = new HashMap<>();
    for (RequirementRelation rel : relations) {
        UUID sourceId = rel.getSource().getId();
        UUID targetId = rel.getTarget().getId();
        reverseAdj.computeIfAbsent(targetId, k -> new ArrayList<>()).add(sourceId);
    }

    // 4. Find all reachable nodes from seed using BFS
    Set<UUID> reachableIds = GraphAlgorithms.findReachable(requirementId, reverseAdj);

    // 5. Fetch and return all impacted requirements
    List<Requirement> fetched = requirementRepository.findAllById(reachableIds);
    return new HashSet<>(fetched);
}
```

**How It Works:**
1. Builds reverse dependency graph (reverse edges of PARENT, DEPENDS_ON, REFINES relations)
2. Uses BFS to find all nodes reachable from the seed requirement
3. "Reachable" = all requirements that depend (directly or transitively) on the seed
4. Returns set of all impacted requirements

**DAG_TYPES Constant (Line 35-36):**
```java
private static final List<RelationType> DAG_TYPES =
    List.of(`RelationType.PARENT`, `RelationType.DEPENDS_ON`, `RelationType.REFINES`);
```

---

### AssetTopologyService - Asset Impact Analysis
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetTopologyService.java` (Lines 19-129)

**Key Method: `computeImpactAnalysis(UUID projectId, UUID assetId)` (Lines 85-98)**

```java
private Set<OperationalAsset> computeImpactAnalysis(UUID projectId, UUID assetId) {
    // 1. Get all active relations for project
    List<AssetRelation> relations = relationRepository.findActiveByProjectId(projectId);

    // 2. Build reverse adjacency map (target -> sources that depend on it)
    Map<UUID, List<UUID>> reverseAdj = new HashMap<>();
    for (AssetRelation rel : relations) {
        UUID sourceId = rel.getSource().getId();
        UUID targetId = rel.getTarget().getId();
        reverseAdj.computeIfAbsent(targetId, k -> new ArrayList<>()).add(sourceId);
    }

    // 3. Find all reachable assets from seed
    Set<UUID> reachableIds = GraphAlgorithms.findReachable(assetId, reverseAdj);

    // 4. Fetch and return assets
    return assetRepository.findAllById(reachableIds).stream().collect(Collectors.toSet());
}
```

**Public Methods:**
- `impactAnalysis(UUID projectId, UUID assetId)` - project-scoped analysis
- `impactAnalysis(UUID assetId)` - DEPRECATED fallback to compute from asset's project

**Related Methods:**
- `detectCycles(UUID projectId)` - detects cycles in asset topology
- `extractSubgraph(UUID projectId, List<String> rootUids)` - bidirectional subgraph extraction

---

### GraphAlgorithms Utility Class
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphAlgorithms.java`

**Purpose:** Pure graph algorithms (no Spring dependencies) with JML contracts

**Key Static Methods:**

1. **`findCycles(Map<UUID, List<UUID>> adjacencyList)` → List<List<UUID>>`**
   - Uses DFS with three-color marking (WHITE=unvisited, GRAY=visiting, BLACK=done)
   - Reconstructs cycles by backtracking when back edge found
   - Returns list of cycles, each as ordered list of UUIDs

2. **`findReachable(UUID start, Map<UUID, List<UUID>> adjacencyList)` → Set<UUID>`**
   - BFS from start node
   - Returns all reachable nodes including start itself
   - **Used by impact analysis**

3. **`findReachableFromMultiple(Set<UUID> roots, Map<UUID, List<UUID>> adjacencyList)` → Set<UUID>`**
   - BFS from multiple starting points
   - Returns all reachable nodes from any root

4. **`topologicalSort(Map<UUID, List<UUID>> dependsOn, Comparator<UUID> tieBreaker)` → List<UUID>`**
   - Kahn's algorithm with tie-breaking
   - Computes in-degree dynamically
   - Omits nodes involved in cycles
   - **Used by work order generation**

---

## 8. MIXED GRAPH SERVICE (Cross-Entity Path Finding)

**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/MixedGraphService.java` (Lines 22-179)

**Purpose:** Provides graph traversal and path finding across mixed entity types (requirements, assets, risks, controls, etc.)

**Key Methods:**

1. **`findPaths(UUID projectId, String sourceNodeId, String targetNodeId, int maxDepth, List<String> entityTypeNames)` → List<GraphPathResult>` (Lines 46-88)**
   - BFS to find path from source to target node
   - Filters projection by entity types if provided
   - Returns single path if found (or empty list)
   - Each path includes edge type information

2. **`neighborhoodProjection(UUID projectId, List<String> rootNodeIds, int depth)` → GraphProjection`** (Lines 90-128)
   - BFS traversal from root nodes up to max depth
   - Returns subgraph containing all reachable nodes and edges

3. **`traverse(UUID projectId, List<String> roots, int maxDepth, List<String> entityTypeNames)` → GraphProjection`**
   - Wrapper for neighborhood projection with filtering

4. **`extractSubgraph(UUID projectId, List<String> roots)` → GraphProjection`**
   - Delegates to MixedGraphClient

5. **`getVisualization(UUID projectId)` → GraphProjection`**
   - Delegates to MixedGraphClient (gets full graph)

**Helper Methods:**
- `buildAdjacency(List<GraphEdge> edges)` → Map<String, Set<String>>
  - Creates adjacency map from edge list

- `buildEdgeLookup(List<GraphEdge> edges)` → Map<String, GraphEdge>
  - Creates bidirectional edge lookup for quick access

- `undirectedKey(String id1, String id2)` → String
  - Creates sorted pair key for bidirectional edge lookup

---

## 9. GRAPH MODELS

### GraphNode Record
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphNode.java`

```java
public record GraphNode(
    String id,                          // Graph node ID
    String domainId,                    // UUID as string
    GraphEntityType entityType,         // Entity type in graph
    String projectIdentifier,           // Project ID as string
    String uid,                         // Human-readable UID (for example, "REQ-001")
    String label,                       // Display label
    Map<String, Object> properties      // Flexible metadata
)
```

---

### GraphEdge Record
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEdge.java`

```java
public record GraphEdge(
    String id,                          // Edge ID
    String edgeType,                    // Relation/link type (for example, "DEPENDS_ON")
    String sourceId,                    // Source node ID
    String targetId,                    // Target node ID
    GraphEntityType sourceEntityType,   // Source entity type
    GraphEntityType targetEntityType,   // Target entity type
    Map<String, Object> properties      // Flexible metadata
)
```

---

### GraphEntityType Enum
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEntityType.java`

```
REQUIREMENT
OPERATIONAL_ASSET
OBSERVATION
RISK_SCENARIO
RISK_REGISTER_RECORD
RISK_ASSESSMENT_RESULT
TREATMENT_PLAN
METHODOLOGY_PROFILE
CONTROL
CONTROL_LINK
VERIFICATION_RESULT
```

---

### GraphProjection Record
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphProjection.java`

```java
public record GraphProjection(
    List<GraphNode> nodes,
    List<GraphEdge> edges
)
```

Represents a subgraph/projection of the full mixed graph

---

## 10. REST CONTROLLERS

### AnalysisController
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java`

**Endpoints:**
- `GET /api/v1/analysis/cycles` - Detect cycles in requirement DAG
- `GET /api/v1/analysis/orphans` - Find requirements with no relations
- `GET /api/v1/analysis/coverage-gaps?linkType=...` - Find requirements missing specific link types
- `GET /api/v1/analysis/impact/{id}` - **IMPACT ANALYSIS** - get all impacted requirements
- `GET /api/v1/analysis/consistency-violations` - Detect CONFLICTS_WITH/SUPERSEDES violations
- `GET /api/v1/analysis/completeness` - Analyze requirement field completeness
- `GET /api/v1/analysis/cross-wave` - Validate cross-wave dependencies
- `GET /api/v1/analysis/work-order` - Get topologically sorted work order
- `GET /api/v1/analysis/dashboard-stats` - Get requirement statistics
- `GET /api/v1/analysis/semantic-similarity` - Find semantically similar requirements

---

### AssetController
**File:** `/home/atomik/src/Ground-Control/backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetController.java`

**Key Impact Analysis Endpoints:**
- `POST /api/v1/assets/links` - Create asset link
- `GET /api/v1/assets/{id}/links` - Get links from asset
- `GET /api/v1/assets/links/target` - Get links by target
- `POST /api/v1/assets/cycles` - **CYCLE DETECTION** - detect cycles in asset topology
- `GET /api/v1/assets/impact-analysis/{id}` - **IMPACT ANALYSIS** - get impacted assets
- `GET /api/v1/assets/subgraph` - Extract asset subgraph

---

## 11. KEY RELATIONSHIPS AND FLOWS

### Traceability Link Flow
```
Requirement ←→ TraceabilityLink ←→ External Artifact
    (via requirement_id)              (via artifactType + artifactIdentifier)

Traceability Coverage Analysis:
- Find requirements with TESTS link type → coverage of TESTS
- Find requirements missing specific link types → coverage gaps
```

### Requirement Impact Analysis Flow
```
1. User queries impact of Requirement A
2. Service finds all RequirementRelations with A as target
   (where relationType in [PARENT, DEPENDS_ON, REFINES])
3. Reverses edges: creates target→sources map
4. BFS from A through reverse edges → finds all downstream dependents
5. Returns all requirements that depend (directly/transitively) on A
```

### Asset Impact Analysis Flow
```
1. User queries impact of Asset X
2. Service finds all AssetRelations with X as target
3. Reverses edges: creates target→sources map
4. BFS from X through reverse edges → finds all downstream dependents
5. Returns all assets that depend (directly/transitively) on X
```

### Cross-Entity Traceability
```
Asset ←→ AssetLink ←→ Requirement (via targetEntityId)
Asset ←→ AssetLink ←→ Control
Asset ←→ AssetLink ←→ RiskScenario
...

Supports linking operational assets to any modeled entity type
```

---

## 12. KEY REPOSITORIES

**Requirement-side:**
- RequirementRepository - fetch requirements by ID, project, status, etc.
- RequirementRelationRepository - manage requirement dependency relations
- TraceabilityLinkRepository - manage requirement-to-artifact links

**Asset-side:**
- OperationalAssetRepository - manage assets
- AssetRelationRepository - manage asset-to-asset topology
- AssetLinkRepository - manage asset-to-entity links
- AssetExternalIdRepository - manage external IDs for assets

---

## 13. IMPACT ANALYSIS ALGORITHM SUMMARY

### Common Pattern (Both Requirement and Asset)

**Input:** ID of entity (Requirement or Asset)

**Algorithm:**
```
1. Fetch entity, verify exists, get project ID
2. Fetch all active relations for project
3. Build reverse adjacency map:
   - For each relation (source → target):
     - Add source to reverseAdj[target]
4. BFS from input entity through reverse edges:
   - visited = {}
   - queue = [input]
   - while queue not empty:
     - current = queue.pop()
     - for each upstream in reverseAdj[current]:
       - if upstream not visited:
         - mark visited
         - queue.push(upstream)
5. Return all visited entities

**Time Complexity:** O(V + E) where V = entities, E = relations
**Space Complexity:** O(V) for visited set and queue
```

**Why Reverse Edges?**
- Forward edges show what depends on what
- To find "impact of X" = find all that depend on X
- Must traverse reverse edges (upstream → downstream)

---

## 14. GRAPH TRAVERSAL & PATH FINDING

**Mixed Graph Service** provides uniform interface for:
- **Path Finding:** BFS between any two graph nodes
- **Neighborhood Extraction:** BFS up to depth N from root nodes
- **Entity Type Filtering:** Include/exclude entity types in results
- **Bidirectional Lookup:** Fast edge type resolution

**Supported Entity Types in Graph:**
- REQUIREMENT
- OPERATIONAL_ASSET
- OBSERVATION
- RISK_SCENARIO
- RISK_REGISTER_RECORD
- RISK_ASSESSMENT_RESULT
- TREATMENT_PLAN
- METHODOLOGY_PROFILE
- CONTROL
- CONTROL_LINK
- VERIFICATION_RESULT

---

## 15. ANALYSIS SERVICE CAPABILITIES

Beyond impact analysis, AnalysisService provides:

1. **Cycle Detection** - DFS-based cycle finding
2. **Orphan Detection** - requirements with no relations
3. **Coverage Gaps** - requirements missing specific link types
4. **Consistency Violations** - CONFLICTS_WITH on ACTIVE items, etc.
5. **Completeness Analysis** - missing required fields
6. **Cross-Wave Validation** - dependency ordering issues
7. **Work Order Generation** - topologically sorted by dependencies and priority
8. **Dashboard Statistics** - counts by status, wave, coverage metrics
9. **Subgraph Extraction** - connected component extraction
10. **Graph Visualization** - convert to GraphProjection for UI

---

## 16. KEY FILE LOCATIONS SUMMARY

**Core Models:**
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/TraceabilityLink.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/RequirementRelation.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/Requirement.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/assets/model/OperationalAsset.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetLink.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetRelation.java`

**State Enums:**
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/ArtifactType.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/LinkType.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/SyncStatus.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/RelationType.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetLinkType.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetLinkTargetType.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetRelationType.java`

**Services:**
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphAlgorithms.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetService.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetTopologyService.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/graph/service/MixedGraphService.java`

**Controllers:**
- `/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java`
- `/src/main/java/com/keplerops/groundcontrol/api/assets/AssetController.java`
- `/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementGraphController.java`

**Repositories:**
- `/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/TraceabilityLinkRepository.java`

**Graph Models:**
- `/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphNode.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEdge.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEntityType.java`
- `/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphProjection.java`
