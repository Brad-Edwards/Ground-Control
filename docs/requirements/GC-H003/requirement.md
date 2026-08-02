---
id: GC-H003
title: "Threat-to-Code Traceability"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:14:02.607666Z
updated_at: 2026-05-21T07:20:46.193191Z
---

# GC-H003 — Threat-to-Code Traceability

## Statement

The system shall support tracing threat-model entries and risk scenarios through affected operational assets and requirements to implementing code, configuration, issues, and controls, providing end-to-end visibility from threat identification to mitigation implementation.

## Rationale

Security assurance requires demonstrating not just that a threat exists, but which service, identity, repository, configuration, and control path addresses it. Asset-grounded graph traceability closes that loop.

## Traceability

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
- IMPLEMENTS → GITHUB_ISSUE `735` (GC-H003: Threat-to-Code Traceability)
