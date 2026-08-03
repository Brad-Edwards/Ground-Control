---
id: GC-T011
title: "FAIR Quantitative Risk Analysis"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T01:53:34.320572Z
updated_at: 2026-08-02T18:39:44.325070Z
---

# GC-T011 — FAIR Quantitative Risk Analysis

## Statement

The system shall support FAIR-aligned quantitative analysis of risk scenarios, including loss event frequency and probable loss magnitude, with underlying factor support for threat event frequency, contact frequency, probability of action, susceptibility, threat capability, resistance strength, primary loss, and secondary loss. FAIR assessments shall support range-based or distribution-based estimates, percentile outputs, and monetary reporting suitable for business decision-making.

## Rationale

Explicit FAIR support requires more than a 5x5 matrix with renamed labels. FAIR defines risk in terms of probable future loss frequency and magnitude and relies on factor-based quantitative analysis with uncertainty-aware estimates.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/FairQuantitativeAnalysisService.java` (FairQuantitativeAnalysisService — FAIR engine)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/FairQuantitativeAnalysisResult.java` (FairQuantitativeAnalysisResult — FAIR result model)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/grcanalysis/FairQuantitativeAnalysisResponse.java` (FairQuantitativeAnalysisResponse — REST response DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/grcanalysis/GrcAnalysisController.java` (GrcAnalysisController — REST endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/GrcAnalysisService.java` (GrcAnalysisService — FAIR analysis delegation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/MethodologyProfileService.java` (MethodologyProfileService — FAIR sub-factor schema support)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V136__extend_fair_input_schema_subfactors.sql` (V136 migration — extend FAIR input schema with sub-factors)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP index.js — gc_analyze fair_quantitative kind)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib.js — analyzeFairQuantitative helper)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/FairQuantitativeAnalysisServiceTest.java` (FairQuantitativeAnalysisServiceTest — unit tests for FAIR engine)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GrcAnalysisControllerTest.java` (GrcAnalysisControllerTest — unit tests for REST endpoint)
- TESTS → TEST `mcp/ground-control/gc-analyze.test.js` (gc-analyze.test.js — MCP gc_analyze integration tests)
- DOCUMENTS → GITHUB_ISSUE `#723` (GC-T011: FAIR Quantitative Risk Analysis)
