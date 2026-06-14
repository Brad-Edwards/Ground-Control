# ADR-020: Asset Cross-Entity Linking

## Status

Accepted

## Date

2026-04-01

## Context

GC-M010 requires operational assets to be linkable to requirements, controls, risk scenarios, threat-model entries, findings, evidence, audits, and external artifacts. ADR-019 anticipated this need and identified it as a risk: "Cross-entity-type relations (asset-to-requirement) may be needed for unified system model traversal - can be addressed via TraceabilityLink or a new linking mechanism."

The existing linking mechanisms are:

- **AssetRelation**: Links assets to other assets (typed, directed) and carries first-class lifecycle/provenance metadata for intra-asset topology.
- **TraceabilityLink**: Links requirements to external artifacts (code files, tests, ADRs, GitHub issues). Requirement-centric with sync status and GitHub integration concerns.

Neither mechanism supports linking an asset to a requirement, control, finding, or other entity type. Many of the target entity types (controls, risk scenarios, findings, evidence) do not yet exist as first-class entities in the system.

## Decision

### 1. New AssetLink Entity

Create an `AssetLink` entity in `domain/assets/model/` that links an operational asset to any entity type via a type discriminator and string identifier pattern. This follows the same architectural approach as `TraceabilityLink` (which uses `ArtifactType` + `artifactIdentifier`) but is anchored on the asset side.

### 2. Separate from TraceabilityLink

AssetLink is a new entity rather than an extension of TraceabilityLink because:

- **Domain separation**: TraceabilityLink lives in `domain/requirements/` and has requirement-specific concerns (SyncStatus, lastSyncedAt, GitHub sync). AssetLink lives in `domain/assets/` with no cross-domain coupling.
- **Different semantics**: TraceabilityLink describes how code artifacts trace to requirements. AssetLink describes how operational assets relate to governance entities (controls, risks, findings).
- **Architecture enforcement**: ArchUnit rules prevent cross-domain imports within the domain layer. A shared entity would require restructuring the package hierarchy.

### 3. Forward-Compatible String Identifiers

Since many target entity types (controls, risk scenarios, findings) do not yet exist as database tables, AssetLink uses string-based `targetIdentifier` and `targetType` discriminator rather than foreign keys. When those entities materialize, reverse lookups via `(targetType, targetIdentifier)` queries will connect them.

### 4. Target Types and Link Types

**Target types** map to the entity categories from GC-M010: REQUIREMENT, CONTROL, RISK_SCENARIO, THREAT_MODEL_ENTRY, FINDING, EVIDENCE, AUDIT, EXTERNAL.

**Link types** describe the nature of the relationship: IMPLEMENTS, MITIGATES, SUBJECT_OF, EVIDENCED_BY, GOVERNED_BY, DEPENDS_ON, ASSOCIATED. These are distinct from TraceabilityLink's LinkType enum (IMPLEMENTS, TESTS, DOCUMENTS, CONSTRAINS, VERIFIES) because the asset-to-entity relationships are semantically different.

## Consequences

**Positive:**
- Assets can be linked to any entity type, satisfying GC-M010's linkability requirement
- Forward-compatible with entity types that don't exist yet
- No cross-domain coupling; clean separation from TraceabilityLink
- Full audit trail via Hibernate Envers
- Reverse lookup enables "which assets are linked to requirement X?" queries

**Negative:**
- No referential integrity enforcement to target entities (string identifiers can reference non-existent entities)
- Two linking mechanisms (TraceabilityLink, AssetLink) that share conceptual similarity but differ in anchor entity

**Risks:**
- If a unified system model graph is needed in the future, a common abstraction layer may need to bridge TraceabilityLink and AssetLink for cross-entity traversal
