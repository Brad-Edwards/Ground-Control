---
id: GC-H002
title: "Threat Scenario-Requirement Linking"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:13:59.916472Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-H002 — Threat Scenario-Requirement Linking

## Statement

The system shall support linking threat-model entries and risk scenarios to operational assets and to requirements, enabling traceability from identified threat sources and threat events through affected assets or boundaries to the requirements, controls, and artifacts that constrain or mitigate them.

## Rationale

Requirements mitigate specific threat scenarios affecting specific operational objects, not abstract labels. Linking threat-model outputs and risk scenarios to both assets and requirements preserves engineering traceability without collapsing threat modeling into the risk register.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#734` (GC-H002: Threat Scenario-Requirement Linking)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/threatmodels/ThreatModelController.java` (ThreatModelController — threat scenario link endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/service/ThreatModelService.java` (ThreatModelService — threat scenario-requirement linking logic)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/repository/ThreatModelLinkRepository.java` (ThreatModelLinkRepository — requirement link persistence)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskScenarioService.java` (RiskScenarioService — requirement linking integration)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-threat-model.js` (gc-threat-model.js — MCP tool handlers for threat scenario-requirement linking)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ThreatModelControllerTest.java` (ThreatModelControllerTest — threat scenario-requirement linking endpoint tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelServiceTest.java` (ThreatModelServiceTest — threat scenario-requirement linking service tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskScenarioServiceTest.java` (RiskScenarioServiceTest — requirement linking integration tests)
- TESTS → TEST `mcp/ground-control/gc-threat-model.test.js` (gc-threat-model.test.js — MCP threat scenario-requirement linking tests)
