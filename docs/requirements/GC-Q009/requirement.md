---
id: GC-Q009
title: "Risk Scenario Workspace"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-30T02:53:40.928431Z
updated_at: 2026-08-02T18:39:44.325058Z
---

# GC-Q009 — Risk Scenario Workspace

## Statement

The web application shall provide a Risk Scenario workspace for humans to create, review, compare, and manage risk scenarios, their linked operational assets, assessments, controls, findings, treatments, and supporting evidence.

## Rationale

A graph-native risk model is not usable if humans cannot see and work the scenario, asset, control, and evidence relationships directly. A dedicated workspace is the human-facing projection of the shared graph model.

## Traceability

- TESTS → TEST `frontend/src/pages/__tests__/risk-scenario-workspace.test.tsx` (Risk Scenario Workspace page test)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskScenarioWorkspaceService.java` (RiskScenarioWorkspaceService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskScenarioWorkspaceController.java` (RiskScenarioWorkspaceController)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/risk-scenario-workspace.tsx` (Risk Scenario Workspace page)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskScenarioWorkspaceServiceTest.java` (RiskScenarioWorkspaceServiceTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskScenarioWorkspaceControllerTest.java` (RiskScenarioWorkspaceControllerTest)
- DOCUMENTS → GITHUB_ISSUE `#747` (GC-Q009: Risk Scenario Workspace)
