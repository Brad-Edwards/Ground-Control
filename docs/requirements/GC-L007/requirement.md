---
id: GC-L007
title: "MCP GRC Analysis Tools"
status: DEPRECATED
type: INTERFACE
priority: MUST
wave: 5
created_at: 2026-03-14T16:56:33.288348Z
updated_at: 2026-07-11T23:43:44.555603Z
---

# GC-L007 — MCP GRC Analysis Tools

## Statement

The system shall expose GRC-specific analysis capabilities as MCP tools, consolidated under the action-discriminated style established by ADR-035. The MCP analysis surface shall include: evidence freshness reports over EvidenceArtifact derivation/supersession timestamps and Observation observed/expires bounds, observation-driven asset-exposure and control-state projection over current Observation state, vendor risk aggregation over OperationalAsset records of AssetType.THIRD_PARTY (and their attached findings, observations, evidence, and controls), asset-aware graph traversal, and mixed-entity path analysis. Methodology-aware risk assessment results shall be queryable via the existing risk-governance and gc_query surfaces; every analysis result shall remain structured and attributable to the methodology, freshness rule, or derivation method used so agents do not confuse incommensurate outputs. Future first-class methodology execution engines and analysis aggregates that are not yet present in the REST API are tracked as separate requirements and are explicitly out of scope here: FAIR quantitative analysis execution is tracked in GC-T011, FAIR-CAM-aligned control analytics in GC-I017, NIST SP 800-30-style assessment workflow execution in GC-T014, compliance posture by framework in GC-I002, cross-framework gap analysis in GC-I007, and MCP parity for the underlying compliance-framework-mapping aggregate in GC-L011. Each of those requirements ships the methodology engine or aggregate together with its MCP/REST exposure.

## Rationale

Agents need to analyze GRC data programmatically without losing methodological integrity. In a graph-native factory, those analyses must include asset-aware graph and state reasoning, not only classic risk and compliance reports. The original statement enumerated methodology execution engines (FAIR quantitative analysis, FAIR-CAM control analytics, NIST SP 800-30 assessment workflows) and analysis aggregates (compliance posture by framework, cross-framework gap analysis) that do not have first-class backend services or REST aggregates in the current codebase; the codex architecture preflight (architecture/notes/mcp-grc-analysis-tools-preflight.md) ruled that MCP cannot satisfy these by inventing an execution engine or tunneling missing aggregates through metadata at the MCP layer. Those engines have been carved out to GC-T011, GC-T014, GC-I017, GC-I002, GC-I007, and GC-L011, whose GitHub issues have been updated to include MCP/REST extension scope so each engine + its MCP exposure ship together. This requirement reflects the consolidated MCP analysis surface that rides on current substrates: EvidenceArtifact/Observation freshness, Observation projection (asset exposure and control state), OperationalAsset-based vendor risk aggregation, plus the existing gc_graph traversal and path analyses.

## Traceability

- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GrcAnalysisControllerTest.java`
- IMPLEMENTS → PULL_REQUEST `#930` (feat(GC-L007): add MCP GRC analysis tools)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/GrcAnalysisService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/EvidenceFreshnessAnalysisService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/ObservationProjectionService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/VendorRiskAggregationService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/grcanalysis/GrcAnalysisController.java`
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js`
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js`
- DOCUMENTS → DOCUMENTATION `architecture/notes/mcp-grc-analysis-tools-preflight.md`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/EvidenceFreshnessAnalysisServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/ObservationProjectionServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/VendorRiskAggregationServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/grcanalysis/GrcAnalysisIntegrationTest.java`
- TESTS → TEST `mcp/ground-control/gc-analyze.test.js`
- IMPLEMENTS → GITHUB_ISSUE `#219` (GC-L007: MCP GRC Analysis Tools)
