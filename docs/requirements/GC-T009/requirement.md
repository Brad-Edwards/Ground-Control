---
id: GC-T009
title: "Risk Scenario Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T01:53:34.119598Z
updated_at: 2026-04-02T05:24:18.910754Z
---

# GC-T009 — Risk Scenario Entity

## Statement

The system shall support a first-class Risk Scenario entity representing a scoped statement of potential future loss tied to one or more affected operational assets, boundaries, processes, systems, objectives, or third parties within a defined time horizon. A risk scenario shall record at minimum threat source or actor, threat event or method, affected object, vulnerability, exposure, or resistance condition when applicable, effect or consequence description, supporting observations or evidence, and links to related topology context. Risk scenarios shall be linkable to threat models, vulnerabilities, controls, findings, evidence, audits, and risk register records.

## Rationale

FAIR, NIST SP 800-30, and ISO-style risk methods all require risk to be scoped to a scenario rather than a vague label. Anchoring the scenario to operational assets, observations, and topology is the minimum structural fix needed to support multiple methodologies without semantic collapse.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskScenario.java` (RiskScenario entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskScenarioLink.java` (RiskScenarioLink entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/RiskScenarioStatus.java` (RiskScenarioStatus enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/RiskScenarioLinkTargetType.java` (RiskScenarioLinkTargetType enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/RiskScenarioLinkType.java` (RiskScenarioLinkType enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/repository/RiskScenarioRepository.java` (RiskScenarioRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/repository/RiskScenarioLinkRepository.java` (RiskScenarioLinkRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskScenarioService.java` (RiskScenarioService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskScenarioLinkService.java` (RiskScenarioLinkService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskScenarioController.java` (RiskScenarioController)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskScenarioLinkController.java` (RiskScenarioLinkController)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V039__create_risk_scenario.sql` (Risk scenario table migration)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V040__create_risk_scenario_audit.sql` (Risk scenario audit table migration)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V041__create_risk_scenario_link.sql` (Risk scenario link table migration)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V042__create_risk_scenario_link_audit.sql` (Risk scenario link audit table migration)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool registrations for risk scenarios)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP API functions for risk scenarios)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskScenarioControllerTest.java` (RiskScenarioController unit test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskScenarioLinkControllerTest.java` (RiskScenarioLinkController unit test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskScenarioServiceTest.java` (RiskScenarioService unit test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskScenarioLinkServiceTest.java` (RiskScenarioLinkService unit test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskScenarioStatusTest.java` (RiskScenarioStatus state machine test)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-risk-scenario.js` (gc_risk_scenario MCP adapter (extracted handler))
- TESTS → TEST `mcp/ground-control/gc-risk-scenario.test.js` (gc_risk_scenario MCP adapter test)
