# ADR-011: Requirements Data Model

## Status

Accepted

## Date

2026-03-08

## Revision

2026-03-09 - Implementation details updated to reflect ADR-013 (Java/Spring Boot rewrite). Core decisions unchanged.
2026-04-10 - Traceability identifier conventions updated to match the implemented service, ADR reverse-lookup, and GitHub sync contracts.
2026-05-10 - Status drift analysis boundary added: derived implementation evidence is a sweep finding, not a traceability edge or lifecycle mutation.

## Context

Ground Control needs a requirements management system to replace the archived StrictDoc tool and issue-graph tool with in-app functionality. This is the first domain code.

The system must:

- Model requirements with human-readable UIDs, parent/child DAG relations, and traceability to external artifacts (GitHub issues, code files, ADRs, configs, tests, TLA+ specs, verification results)
- Sync with GitHub issues for cross-referencing and label-based metadata
- Support graph traversal for impact analysis and coverage gap detection
- Import existing requirements from the archived StrictDoc `project.sdoc` file (~145 requirements)

Key constraints:

- Requirements form a **DAG** (directed acyclic graph), not a tree - a requirement can have multiple parents
- The system must dogfood itself: Ground Control manages its own requirements
- Apache AGE (graph extension for PostgreSQL) is available for optimized traversal queries

## Decision

### 1. UUID Primary Keys

All domain entities use UUID primary keys via JPA `@GeneratedValue(strategy = GenerationType.UUID)`. Better for API exposure (no sequential ID enumeration) and distributed systems (no central sequence coordination).

### 2. Package Structure

The requirements domain lives under `com.keplerops.groundcontrol.domain.requirements`:

```
domain/requirements/
    model/           # JPA entities: Requirement, RequirementRelation
    state/           # Enums: Status, RequirementType, Priority, RelationType
    service/         # RequirementService (write-owner), command records
    repository/      # Spring Data JPA interfaces
```

Future domain areas (verification, risks, controls) follow the same pattern under `domain/`. Shared exception classes live in `domain/exception/`.

### 3. DAG Relations via Junction Entity

Requirements form a DAG, not a tree. Tree libraries cannot model multiple parents. A `RequirementRelation` JPA entity stores typed edges (parent, depends_on, refines, conflicts, supersedes, related) with a unique constraint on `(source_id, target_id, relation_type)`.

### 4. Simple Status Machine

Requirement states (DRAFT, ACTIVE, DEPRECATED, ARCHIVED) are governed by an `EnumMap<Status, Set<Status>>` transition table with JML contract preconditions on `transitionStatus()`. Four states with few valid transitions do not justify a state machine library.

### 5. Audit via Hibernate Envers

Business entities whose historical state matters to analysis (`Requirement`, `RequirementRelation`, `TraceabilityLink`) are annotated with `@Audited` (Hibernate Envers). Envers automatically maintains audit tables and a `revinfo` table. Cache tables (`GitHubIssueSync`) and self-auditing records (`RequirementImport`) track their own history without Envers. Flyway migrations V003-V005 and V009 create the audit schema.

### 6. AGE as Query Layer, JPA as Source of Truth

All CRUD operations go through Spring Data JPA repositories. Apache AGE is a **read-only query layer** materialized from JPA data:

- A materialization service syncs `Requirement` nodes and typed relation edges to AGE
- Graph traversal queries (impact analysis, dependency chains, path finding) use Cypher via `JdbcTemplate`
- Core analysis functions (cycle detection, orphan detection, coverage gaps) also work via JPA for environments without AGE
- This avoids dual-write consistency issues and keeps AGE optional

### 7. GitHub Sync via `gh` CLI

Batch import of GitHub issues via a scheduled service or command that calls `gh api`. No webhook infrastructure. Sufficient for the dogfooding use case.

### 8. Service Layer Write Ownership

All entities in the requirements domain are governed by service-layer ownership: each entity has exactly one service that may write to it. Other services may read via repository queries but never mutate directly.

- `RequirementService` owns `Requirement` and `RequirementRelation`
- `TraceabilityService` owns `TraceabilityLink` (future)
- `SyncService` owns `GitHubIssueSync` (future)
- `ImportService` owns `RequirementImport` and orchestrates cross-service flows (future)
- `AnalysisService` is read-only across all entities (future)

Controllers and orchestration components call services in sequence. Services do not call each other horizontally. No Spring events for cross-service communication.

See [Phase 1 design notes](../notes/phase1-requirements-design.md#service-layer-architecture) for the full rationale and rules.

### 9. Status Drift Is Derived Analysis Evidence

Ground Control keeps `IMPLEMENTS` links restricted to `ACTIVE` requirements. That rule remains load-bearing: `DRAFT` requirements may have implementation-like evidence before formal verification and transition, but that evidence must not be stored by weakening the traceability constraint or by introducing a second implementation-link schema.

Status drift detection is therefore modeled as read-only analysis output. Every signal is derived from data owned by the requirement's own project - its `DRAFT` requirements, their canonical traceability links, and accepted ADR records. It deliberately does **not** read the GitHub issue/PR sync tables, because those caches are not project- or repo-scoped, so reading them from a project-scoped analysis could surface another project's (or another repo's, including a private one's) artifacts. The output carries confidence and evidence artifacts so a user or agent can verify the clauses and then run the normal status transition and traceability reconciliation workflow.

Status drift analysis must not:

- transition requirements automatically
- create `IMPLEMENTS` links for `DRAFT` requirements through the public traceability path
- encode artifacts outside the existing `ArtifactType` / `LinkType` conventions
- bypass project scoping, read project- or repo-unscoped caches (for example the GitHub issue/PR sync tables), or assume UID uniqueness outside a project
- shell out to GitHub or scan arbitrary filesystem paths from the analysis service

### Data Model

Five entities in the requirements domain (first two implemented, remaining three planned):

| Entity | Purpose | Status |
|--------|---------|--------|
| **Requirement** | Core requirement record with lifecycle | Implemented |
| **RequirementRelation** | DAG edges between requirements | Implemented |
| **TraceabilityLink** | Links requirements to external artifacts | Planned |
| **GitHubIssueSync** | Cached GitHub issue data | Planned |
| **RequirementImport** | Audit trail for bulk imports | Planned |

See also [ADR-014](014-pluggable-verification-architecture.md) for `VerificationResult`, a separate domain entity in `domain/verification/` that connects to requirements via TraceabilityLink.

#### Requirement

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUID | PK, generated | `@GeneratedValue(strategy = UUID)` |
| uid | String(50) | Unique, not null | Human-readable: "W2-RISK," "REQ-001" |
| title | String(255) | Not null | |
| statement | TEXT | Not null | |
| rationale | TEXT | Default "" | |
| requirementType | Enum (RequirementType) | Not null, default FUNCTIONAL | functional, non_functional, constraint, interface |
| priority | Enum (Priority) | Not null, default MUST | must, should, could, wont (MoSCoW) |
| status | Enum (Status) | Not null, default DRAFT | draft, active, deprecated, archived |
| wave | Integer | Nullable | StrictDoc import compatibility |
| createdAt | Instant | Not null, `@PrePersist` | |
| updatedAt | Instant | Not null, `@PreUpdate` | |
| archivedAt | Instant | Nullable | Soft delete timestamp |

**JML invariant**: `archivedAt == null || status == Status.ARCHIVED`

**Status transitions** (enforced via JML contracts + EnumMap):
- DRAFT -> ACTIVE
- ACTIVE -> DEPRECATED
- ACTIVE -> ARCHIVED (soft delete)
- DEPRECATED -> ARCHIVED
- No transitions out of ARCHIVED
- No backward transitions

#### RequirementRelation

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUID | PK, generated | |
| source | FK -> Requirement | Not null | `@ManyToOne` |
| target | FK -> Requirement | Not null | `@ManyToOne` |
| relationType | Enum (RelationType) | Not null | parent, depends_on, refines, conflicts, supersedes, related |
| description | TEXT | Default "" | |
| createdAt | Instant | Not null, `@PrePersist` | |

**Unique constraint**: `(source_id, target_id, relation_type)`
**Validation**: `source != target` (no self-loops, enforced in service layer). Cycle detection is an analysis-time check, not a save-time constraint.

#### TraceabilityLink

Connects requirements to external artifacts. `artifactIdentifier` is interpreted in the context of `artifactType`; it is not a globally prefixed mini-schema.

Canonical conventions:
- `GITHUB_ISSUE`, `PULL_REQUEST` - raw decimal number stored as a string (for example `"42"`), so sync services can parse and refresh URL/title/state
- `ADR`, `RISK_SCENARIO`, `CONTROL` - the Ground Control UID (for example `"ADR-021"`, `"RS-001"`, `"CTRL-001"`), so reverse lookup resolves by domain UID
- `CODE_FILE`, `TEST`, `CONFIG`, `POLICY`, `SPEC`, `PROOF`, `DOCUMENTATION` - repo-relative path or other stable repo-local identifier
- `artifactUrl` and `artifactTitle` are denormalized display/sync fields, not identity keys

Do not introduce alternate encodings for the same artifact (`#42`, `owner/repo#42`, `file:...`, `adr:021`) in new data. Self-referential dogfooding must reuse this existing link model rather than adding a GC-specific traceability abstraction.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUID | PK, generated | |
| requirement | FK -> Requirement | Not null | |
| artifactType | Enum (ArtifactType) | Not null | github_issue, code_file, adr, config, policy, test, spec, proof, documentation |
| artifactIdentifier | String(500) | Not null | Stable identifier scoped by `artifactType` |
| artifactUrl | String(2000) | Default "" | |
| artifactTitle | String(255) | Default "" | |
| linkType | Enum (LinkType) | Not null | implements, tests, documents, constrains, verifies |
| syncStatus | Enum (SyncStatus) | Default SYNCED | synced, stale, broken |
| lastSyncedAt | Instant | Nullable | |
| createdAt | Instant | Not null | |
| updatedAt | Instant | Not null | |

#### GitHubIssueSync (planned)

Cached mirror of GitHub issue data. Updated by the sync service.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUID | PK, generated | |
| issueNumber | Integer | Unique | |
| issueTitle | String(500) | | |
| issueState | Enum (IssueState) | | open, closed |
| issueLabels | JSONB | Default [] | |
| issueBody | TEXT | Default "" | |
| issueUrl | String(2000) | | |
| phase | Integer | Nullable | |
| priorityLabel | String(10) | Default "" | |
| crossReferences | JSONB | Default [] | |
| lastFetchedAt | Instant | Not null | |
| createdAt | Instant | Not null | |
| updatedAt | Instant | Not null | |

#### RequirementImport (planned)

Audit trail for each import/sync operation.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUID | PK, generated | |
| sourceType | Enum (ImportSourceType) | Not null | strictdoc, github, manual |
| sourceFile | String(500) | Default "" | |
| importedAt | Instant | Not null | |
| stats | JSONB | Default {} | `{"created": 10, "updated": 5, "skipped": 2}` |
| errors | JSONB | Default [] | `[{"uid": "REQ-001", "error": "..."}]` |

## Consequences

### Positive

- DAG model is more expressive than a tree - accurately represents real requirement relationships
- UUID PKs are safe for API exposure and future multi-tenant distribution
- AGE-as-query-layer avoids consistency issues while enabling powerful graph queries
- Envers provides automatic audit trail with minimal configuration
- Service-layer write ownership prevents mutation spaghetti without premature package splitting
- Artifact-type-scoped identifiers keep reverse lookup and sync flows simple while accommodating code, docs, ADRs, and verification artifacts without a second schema layer
- Status drift can surface lifecycle bookkeeping rot without confusing evidence with authoritative traceability or lifecycle state

### Negative

- DAG cycle detection must be implemented manually (no library enforcement)
- AGE sync adds a materialization step that can drift if not run after JPA changes
- `gh` CLI batch sync means GitHub data is always slightly stale (no real-time webhooks)

### Risks

- AGE extension availability varies across PostgreSQL hosting providers (mitigated: core analysis works without AGE via JPA)
- StrictDoc format may have edge cases not covered by the parser (mitigated: import is a one-time migration)
- DAG traversal via JPA adjacency list is O(depth x branching_factor) per query (mitigated: AGE provides O(1)-ish traversal for production use)
- Multiple textual encodings for the same artifact fragment uniqueness and break sync or reverse lookup (mitigated: keep canonical identifier conventions per `artifactType` and route writes through the existing service layer)
- Treating status drift evidence as implementation truth can create false positives or premature lifecycle changes (mitigated: report confidence and artifacts only; verification plus DRAFT -> ACTIVE transition stays explicit)

## Related ADRs

- [ADR-002](002-postgresql-database.md) - PostgreSQL as primary database
- [ADR-005](005-apache-age-graph.md) - Apache AGE for graph capabilities
- [ADR-012](012-formal-methods-process.md) - SDD methodology and assurance levels
- [ADR-013](013-java-spring-boot-rewrite.md) - Java/Spring Boot backend
- [ADR-014](014-pluggable-verification-architecture.md) - Pluggable verification architecture (VerificationResult as connected artifact)
