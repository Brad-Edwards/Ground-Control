---
id: GC-T013
title: "FAIR Risk Scenario Taxonomy Support"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T01:58:19.229945Z
updated_at: 2026-08-02T18:39:44.325080Z
---

# GC-T013 — FAIR Risk Scenario Taxonomy Support

## Statement

The system shall support FAIR-aligned scenario scoping for cyber risk scenarios using explicit threat, asset, method, effect, and time-horizon components, including support for expressing scenarios in the form '[threat] impacts [asset] via [method], causing [effect(s)]'. The system shall distinguish true risk scenarios from control deficiencies, vulnerabilities, audit findings, or generic concerns that do not yet constitute analyzable loss scenarios.

## Rationale

The FAIR Cyber Risk Scenario Taxonomy makes scenario quality a first-class concern because vague register entries produce bad analysis and bad prioritization. Explicit support prevents Ground Control from sliding back into ambiguous pseudo-risk statements.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskScenario.java` (RiskScenario entity (FAIR-CRST scoping aggregate))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskScenarioRequest.java` (RiskScenarioRequest DTO (@Size(min=10) structural quality gate))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskScenarioResponse.java` (RiskScenarioResponse (derived fairSentence projection))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V127__fair_risk_scenario_rename_columns.sql` (V127 Flyway migration (FAIR-CRST column renames and vulnerability drop))
- DOCUMENTS → DOCUMENTATION `architecture/notes/fair-risk-scenario-taxonomy-clause-map.md` (FAIR Risk Scenario Taxonomy Clause Map)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskScenarioControllerTest.java` (RiskScenarioControllerTest (fairSentence projection + @Size + vulnerability-drop))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskScenarioServiceTest.java` (RiskScenarioServiceTest (fairSentence computation + axis update path))
- TESTS → TEST `mcp/ground-control/gc-risk-scenario.test.js` (MCP gc_risk_scenario adapter tests (FAIR-CRST snake/camel mapping + vulnerability drop))
- IMPLEMENTS → PULL_REQUEST `1050` (PR #1050 — reshape RiskScenario to FAIR-CRST axes)
- IMPLEMENTS → GITHUB_ISSUE `#720` (GC-T013: FAIR Risk Scenario Taxonomy Support)
