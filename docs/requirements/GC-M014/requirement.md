---
id: GC-M014
title: "External Identifiers and Source Provenance"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:19.058333Z
updated_at: 2026-04-01T23:28:13.117271Z
---

# GC-M014 — External Identifiers and Source Provenance

## Statement

The system shall support external identifiers and source provenance for operational assets and related topology facts, including source system, source identifier, collection or assertion timestamp, and confidence or quality metadata when available. The model shall support multiple partially overlapping sources without assuming one perfect inventory.

## Rationale

Multi-source inventories only stay sane if asset facts carry provenance, external identifiers, and time-awareness. Cloud asset inventory systems and CMDB ecosystems both rely on this to reconcile overlapping sources and to distinguish fresh facts from stale assertions.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tools (external ID + relation provenance))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib (external ID API functions))

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetExternalId.java` (AssetExternalId entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetRelation.java` (AssetRelation entity (provenance fields))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/repository/AssetExternalIdRepository.java` (AssetExternalId repository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetService.java` (AssetService (external ID CRUD + relation provenance))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetController.java` (AssetController (external ID endpoints + relation provenance))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V031__create_asset_external_id.sql` (Migration: asset_external_id table)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V033__add_provenance_to_asset_relation.sql` (Migration: provenance columns on asset_relation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AssetControllerTest.java` (AssetController unit tests (external IDs + relation provenance))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetServiceTest.java` (AssetService unit tests (external IDs + relation provenance))
