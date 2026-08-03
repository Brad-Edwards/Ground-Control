---
id: GC-M017
title: "Asset-Centric Traceability and Impact Context"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:19.358904Z
updated_at: 2026-04-06T17:41:38.393865Z
---

# GC-M017 — Asset-Centric Traceability and Impact Context

## Statement

The system shall support traceability from operational assets to requirements, controls, risk scenarios, threat-model entries, findings, issues, code, configuration, and other artifacts so that impact and assurance analysis can pivot around the protected or operated object rather than only around a requirement record.

## Rationale

A graph-native factory needs to answer questions from the perspective of the affected service, identity, data store, or workload, not only from the perspective of a requirement. Asset-centric traceability is what makes later live-state and change-impact analysis useful to both agents and humans.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetLinkTargetType.java` (AssetLinkTargetType enum (added ISSUE, CODE, CONFIGURATION))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/AssetGraphProjectionContributor.java` (AssetGraphProjectionContributor (fixed CONTROL graph projection))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ControlGraphProjectionContributor.java` (ControlGraphProjectionContributor (control nodes/edges in graph))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/GraphTargetResolverService.java` (GraphTargetResolverService (added ISSUE/CODE/CONFIGURATION validation))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/RiskGraphProjectionContributor.java` (RiskGraphProjectionContributor (fixed CONTROL graph projection))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/repository/ControlLinkRepository.java` (ControlLinkRepository (added findByProjectId for graph projection))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib.js (added ISSUE/CODE/CONFIGURATION to ASSET_LINK_TARGET_TYPES))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GraphTargetResolverServiceTest.java` (GraphTargetResolverServiceTest (new target type validation))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetGraphProjectionContributorTest.java` (AssetGraphProjectionContributorTest (CONTROL edge projection test))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlGraphProjectionContributorTest.java` (ControlGraphProjectionContributorTest (control graph projection tests))
