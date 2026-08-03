---
id: GC-C008
title: "Analysis API Exposure"
status: ACTIVE
type: INTERFACE
priority: MUST
wave: 1
created_at: 2026-03-13T23:12:06.052940Z
updated_at: 2026-03-18T07:06:50.246032Z
---

# GC-C008 — Analysis API Exposure

## Statement

The system shall expose all validation and analysis operations via both REST API and MCP tools with structured, machine-readable results suitable for automated pipelines.

## Rationale

AI agents and CI/CD pipelines must be able to run validations programmatically. Machine-readable results enable gating and automated decision-making.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (AnalysisController - Analysis API endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService — analyzeCompleteness method)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP gc_analyze_completeness tool — backend-backed implementation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest — AnalyzeCompleteness nested class)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (AnalysisIntegrationTest — completeness + coverage-gaps smoke tests)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#343` (GC-C008: Analysis API Exposure)
