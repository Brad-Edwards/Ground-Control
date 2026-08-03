---
id: GC-M012
title: "Asset Ownership Criticality and Scope"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:18.862454Z
updated_at: 2026-05-16T06:29:05.478727Z
---

# GC-M012 — Asset Ownership Criticality and Scope

## Statement

The system shall support recording asset ownership, stewardship, environment, criticality, business or mission context, and scope metadata such as in-scope or out-of-scope designations for assurance activities. Asset ownership and criticality shall be queryable and usable in risk, control, audit, and reporting workflows.

## Rationale

Operational risk and assurance decisions depend on who owns an asset, how critical it is, and what scope it is in. These concepts appear across CMDB, service, audit, and security inventory systems and are difficult to retrofit cleanly if omitted from the requirements foundation.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/OperationalAsset.java` (OperationalAsset entity (GC-M012 owner/steward/environment/criticality/businessContext/scopeDesignation fields))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetCriticality.java` (AssetCriticality enum (GC-M012))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetEnvironment.java` (AssetEnvironment enum (GC-M012))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetScope.java` (AssetScope enum (GC-M012))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetService.java` (AssetService (GC-M012 create/update + clear-flag path + listByProjectAndFilters))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/repository/OperationalAssetRepository.java` (OperationalAssetRepository (GC-M012 filtered query))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetController.java` (AssetController (GC-M012 list filters + clear-flag pass-through))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/AssetGraphProjectionContributor.java` (AssetGraphProjectionContributor (GC-M012 node properties))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V069__add_asset_ownership_criticality_scope.sql` (V069 migration (GC-M012 operational_asset columns))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V070__add_asset_ownership_criticality_scope_audit.sql` (V070 migration (GC-M012 operational_asset_audit parity))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP enum mirrors (ASSET_CRITICALITIES / ASSET_ENVIRONMENTS / ASSET_SCOPES) + clear-flag mappings)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetServiceTest.java` (AssetServiceTest (GC-M012 create/update/clear-flag/filter coverage))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AssetControllerTest.java` (AssetControllerTest (GC-M012 create/update captors + filter params))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetGraphProjectionContributorTest.java` (AssetGraphProjectionContributorTest (GC-M012 node-property coverage))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java` (MigrationSmokeTest (V069/V070 in version list + column-existence probes))
- IMPLEMENTS → GITHUB_ISSUE `#724` (GC-M012: Asset Ownership Criticality and Scope)
