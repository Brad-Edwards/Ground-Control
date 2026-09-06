---
id: GC-M011
title: "Asset Classification and Subtype Extensibility"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:18.761467Z
updated_at: 2026-05-17T20:05:41.609515Z
---

# GC-M011 — Asset Classification and Subtype Extensibility

## Statement

The system shall support asset classification and subtype extensibility, allowing projects to represent operational asset kinds such as service, application, infrastructure resource, workload, identity, data asset, endpoint, integration, or vendor dependency without collapsing them into one flat record. The asset model shall support shared core attributes plus extensible subtype-specific metadata and schema layering where needed.

## Rationale

ServiceNow CSDM, Atlassian Assets, cloud inventories, and security graph products all distinguish multiple operational object types that share some attributes but also need subtype-specific fields. A flat asset model becomes a dead end as soon as identities, data stores, and services need to coexist.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/043-asset-classification-subtype-extensibility.md` (ADR-043: Asset Classification and Subtype Extensibility)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js`
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js`
- IMPLEMENTS → GITHUB_ISSUE `#722` (GC-M011: Asset Classification and Subtype Extensibility)
- IMPLEMENTS → PULL_REQUEST `#917` (feat(assets): add subtype + metadata + schema registry (GC-M011))
- IMPLEMENTS → PULL_REQUEST `#919` (fix(assets): clear 47 SonarCloud findings on GC-M011 work)
- TESTS → TEST `mcp/ground-control/lib.tosnakecase.test.js` (toSnakeCase opaque-value-key guard (GC-M011 subtype metadata))

## Historical traceability

Links below named artifacts the #1500 re-platform deleted. They are kept for
provenance and are outside the parsed `## Traceability` section, so no tool reads
them as live evidence. Do not infer current implementation from them.

- DOCUMENTS → DOCUMENTATION `docs/API.md`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetSubtypeSchema.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/OperationalAsset.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetSubtypeSchemaStatus.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/repository/AssetSubtypeSchemaRepository.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/repository/OperationalAssetRepository.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/validation/AssetSubtypeValidator.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/CreateAssetCommand.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/UpdateAssetCommand.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/CreateAssetSubtypeSchemaCommand.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/UpdateAssetSubtypeSchemaCommand.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/AssetGraphProjectionContributor.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetController.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetRequest.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetResponse.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/UpdateAssetRequest.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetSubtypeSchemaController.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetSubtypeSchemaRequest.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetSubtypeSchemaResponse.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/UpdateAssetSubtypeSchemaRequest.java`
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V080__add_asset_subtype_and_metadata.sql`
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V081__add_asset_subtype_and_metadata_audit.sql`
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V082__create_asset_subtype_schema.sql`
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V083__create_asset_subtype_schema_audit.sql`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetSubtypeValidatorTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AssetControllerTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AssetSubtypeSchemaControllerTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetGraphProjectionContributorTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementsE2EIntegrationTest.java`
