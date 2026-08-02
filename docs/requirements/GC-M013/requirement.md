---
id: GC-M013
title: "Asset Topology and Boundary Relationships"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:18.961829Z
updated_at: 2026-04-01T05:07:37.304479Z
---

# GC-M013 — Asset Topology and Boundary Relationships

## Statement

The system shall support typed relationships among operational assets and boundaries, including containment, dependency, communication, trust boundary, service-support, identity-to-resource access, and data-flow or integration relationships. Topology shall be represented in graph relations rather than only embedded custom fields so that multi-hop impact, threat, and control analysis can traverse the system model.

## Rationale

Asset graphs become brittle when topology is hidden in per-type fields instead of graph relationships. Prior art from service models, cloud inventories, and attack-path products shows that dependency, communication, and boundary relationships are the substrate for meaningful analysis.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetRelation.java` (AssetRelation entity - typed topology relationships)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetTopologyService.java` (AssetTopologyService - multi-hop graph traversal)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/OperationalAsset.java` (OperationalAsset entity)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetServiceTest.java` (AssetService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetTopologyServiceTest.java` (AssetTopologyService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AssetControllerTest.java` (AssetController unit tests)
- DOCUMENTS → ADR `ADR-019` (Asset Topology Model)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetService.java` (AssetService - CRUD and relation management)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetController.java` (AssetController - REST API endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetRelationType.java` (AssetRelationType enum - 7 topology relationship types)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetType.java` (AssetType enum - operational asset types)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/repository/OperationalAssetRepository.java` (OperationalAssetRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/repository/AssetRelationRepository.java` (AssetRelationRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V025__create_operational_asset.sql` (Migration - operational_asset table)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V026__create_asset_relation.sql` (Migration - asset_relation table (graph topology))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool definitions for asset operations)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP API client functions for assets)
- IMPLEMENTS → GITHUB_ISSUE `466` (GC-M013: Asset Topology and Boundary Relationships)
