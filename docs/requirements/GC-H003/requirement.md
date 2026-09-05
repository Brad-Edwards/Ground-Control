---
id: GC-H003
title: "Threat-to-Code Traceability"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:14:02.607666Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-H003 — Threat-to-Code Traceability

## Statement

The system shall support tracing threat-model entries and risk scenarios through affected operational assets and requirements to implementing code, configuration, issues, and controls, providing end-to-end visibility from threat identification to mitigation implementation.

## Rationale

Security assurance requires demonstrating not just that a threat exists, but which service, identity, repository, configuration, and control path addresses it. Asset-grounded graph traceability closes that loop.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `735` (GC-H003: Threat-to-Code Traceability)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/trace/SecurityTrace.java` (SecurityTrace — domain model for threat/risk-to-code trace)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/trace/SecurityTraceSourceType.java` (SecurityTraceSourceType — enum distinguishing threat-model vs risk-scenario trace sources)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/trace/SecurityTraceResponse.java` (SecurityTraceResponse — API response DTO for trace endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/service/ThreatModelService.java` (ThreatModelService — findTrace method for threat-to-code traceability)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskScenarioService.java` (RiskScenarioService — findTrace method for risk-to-code traceability)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/threatmodels/ThreatModelController.java` (ThreatModelController — /trace endpoint for threat-to-code traceability)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskScenarioController.java` (RiskScenarioController — /trace endpoint for risk-to-code traceability)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelServiceTest.java` (ThreatModelServiceTest — tests for findTrace threat-to-code traceability)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskScenarioServiceTest.java` (RiskScenarioServiceTest — tests for findTrace risk-to-code traceability)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ThreatModelControllerTest.java` (ThreatModelControllerTest — tests for /trace endpoint (threat model))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskScenarioControllerTest.java` (RiskScenarioControllerTest — tests for /trace endpoint (risk scenario))
