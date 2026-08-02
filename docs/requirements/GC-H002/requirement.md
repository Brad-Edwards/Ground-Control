---
id: GC-H002
title: "Threat Scenario-Requirement Linking"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:13:59.916472Z
updated_at: 2026-05-21T05:48:28.864979Z
---

# GC-H002 — Threat Scenario-Requirement Linking

## Statement

The system shall support linking threat-model entries and risk scenarios to operational assets and to requirements, enabling traceability from identified threat sources and threat events through affected assets or boundaries to the requirements, controls, and artifacts that constrain or mitigate them.

## Rationale

Requirements mitigate specific threat scenarios affecting specific operational objects, not abstract labels. Linking threat-model outputs and risk scenarios to both assets and requirements preserves engineering traceability without collapsing threat modeling into the risk register.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/threatmodels/ThreatModelController.java` (ThreatModelController — threat scenario link endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/service/ThreatModelService.java` (ThreatModelService — threat scenario-requirement linking logic)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/repository/ThreatModelLinkRepository.java` (ThreatModelLinkRepository — requirement link persistence)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskScenarioService.java` (RiskScenarioService — requirement linking integration)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-threat-model.js` (gc-threat-model.js — MCP tool handlers for threat scenario-requirement linking)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ThreatModelControllerTest.java` (ThreatModelControllerTest — threat scenario-requirement linking endpoint tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelServiceTest.java` (ThreatModelServiceTest — threat scenario-requirement linking service tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskScenarioServiceTest.java` (RiskScenarioServiceTest — requirement linking integration tests)
- TESTS → TEST `mcp/ground-control/gc-threat-model.test.js` (gc-threat-model.test.js — MCP threat scenario-requirement linking tests)
- IMPLEMENTS → GITHUB_ISSUE `#734` (GC-H002: Threat Scenario-Requirement Linking)
