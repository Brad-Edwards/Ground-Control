# FAIR Risk Scenario Taxonomy Clause Map

Issue: #720
Requirement: GC-T013

This note maps each FAIR-CRST implementation clause to its authoritative file and line.

## FAIR-CRST Scoping Axes (Database + Domain)

| Axis | Column | Entity field | File |
|------|--------|--------------|------|
| `threat` | `risk_scenario.threat` | `RiskScenario.threat` | `domain/riskscenarios/model/RiskScenario.java` |
| `method` | `risk_scenario.method` | `RiskScenario.method` | `domain/riskscenarios/model/RiskScenario.java` |
| `asset` | `risk_scenario.asset` | `RiskScenario.asset` | `domain/riskscenarios/model/RiskScenario.java` |
| `effect` | `risk_scenario.effect` | `RiskScenario.effect` | `domain/riskscenarios/model/RiskScenario.java` |
| `timeHorizon` | `risk_scenario.time_horizon` | `RiskScenario.timeHorizon` | `domain/riskscenarios/model/RiskScenario.java` |

Migration: `db/migration/V127__fair_risk_scenario_rename_columns.sql`

## Derived fairSentence

Implemented in `RiskScenario.getFairSentence()` from `domain/riskscenarios/model/RiskScenario.java`.

Template: `{threat} impacts {asset} via {method}, causing {effect}`

This sentence is projected in `api/riskscenarios/RiskScenarioResponse.from(RiskScenario)` and never stored.

## @Size(min=10) Validation

Applied on `threat`, `method`, `asset`, `effect` in:
- `api/riskscenarios/RiskScenarioRequest.java`
- `api/riskscenarios/UpdateRiskScenarioRequest.java`

## Commands

- CreateRiskScenarioCommand in `domain/riskscenarios/service/CreateRiskScenarioCommand.java` has 8 fields: projectId, uid, title, threat, method, asset, effect, timeHorizon.
- UpdateRiskScenarioCommand in `domain/riskscenarios/service/UpdateRiskScenarioCommand.java` has 6 fields: title, threat, method, asset, effect, timeHorizon.

## Graph Projection

Property keys updated in `domain/graph/service/RiskGraphProjectionContributor.java` and the AGE allowlist in `infrastructure/age/AgeGraphService.java`.

## MCP Layer

Field names updated in `mcp/ground-control/gc-risk-scenario.js`; `fair_sentence: "fairSentence"` mapping added to `mcp/ground-control/lib.js` TO_CAMEL table.

## Test Coverage

| Behavior | Test class | Method |
|----------|-----------|--------|
| fairSentence on response | `RiskScenarioControllerTest` | `createResponseIncludesFairSentence` |
| fairSentence domain computation | `RiskScenarioServiceTest` | `Create.fairSentenceIsComputedFromFourAxes` |
| @Size(min=10) threat | `RiskScenarioControllerTest` | `createReturns422WhenThreatTooShort` |
| @Size(min=10) method | `RiskScenarioControllerTest` | `createReturns422WhenMethodTooShort` |
| @Size(min=10) asset | `RiskScenarioControllerTest` | `createReturns422WhenAssetTooShort` |
| @Size(min=10) effect | `RiskScenarioControllerTest` | `createReturns422WhenEffectTooShort` |
| vulnerability silently ignored | `RiskScenarioControllerTest` | `createIgnoresVulnerabilityField` |
| vulnerability absent from MCP body | `gc-risk-scenario.test.js` | `does NOT forward deprecated 'vulnerability' field` |
