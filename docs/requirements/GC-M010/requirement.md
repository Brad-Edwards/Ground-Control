---
id: GC-M010
title: "Operational Asset Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:18.624861Z
updated_at: 2026-04-01T07:57:17.092884Z
---

# GC-M010 — Operational Asset Entity

## Statement

The system shall support a first-class Operational Asset entity representing a protected or operated object in the traceability graph, including services, systems, workloads, identities, data stores, endpoints, integrations, and third-party dependencies. Operational assets shall be linkable to requirements, controls, risk scenarios, threat-model entries, findings, evidence, audits, and external artifacts.

## Rationale

Risk, control, and threat analysis need a concrete protected or operated object to anchor to. Prior art from CMDBs, cloud asset inventories, and graph-native security platforms shows that treating assets as first-class graph entities is the minimum foundation for impact analysis, continuous assurance, and traceability.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tools (gc_create_asset_link, gc_get_asset_links, etc.))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib (asset link API functions + new types))
- DOCUMENTS → ADR `architecture/adrs/020-asset-cross-entity-linking.md` (ADR-020: Asset Cross-Entity Linking)
- IMPLEMENTS → GITHUB_ISSUE `468` (GC-M010: Operational Asset Entity)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetType.java` (AssetType enum (added SYSTEM, WORKLOAD, ENDPOINT, INTEGRATION, THIRD_PARTY))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetLink.java` (AssetLink entity for cross-entity linking)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetLinkTargetType.java` (AssetLinkTargetType enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetLinkType.java` (AssetLinkType enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/repository/AssetLinkRepository.java` (AssetLinkRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetService.java` (AssetService (added link CRUD methods))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetController.java` (AssetController (added link endpoints))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V029__create_asset_link.sql` (V029 migration - asset_link table)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V030__create_asset_link_audit.sql` (V030 migration - asset_link_audit table)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AssetControllerTest.java` (AssetController unit tests (link endpoints))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetServiceTest.java` (AssetService unit tests (link CRUD))
