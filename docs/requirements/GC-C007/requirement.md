---
id: GC-C007
title: "Consistency Violation Detection"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:12:03.389552Z
updated_at: 2026-03-18T06:36:37.242232Z
---

# GC-C007 — Consistency Violation Detection

## Statement

The system shall detect consistency violations including: ACTIVE requirements linked by conflicts_with relations, and requirements linked by supersedes where both are ACTIVE.

## Rationale

Two active requirements that conflict or supersede each other represent a logical contradiction in the specification. Automated detection surfaces these for resolution.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_analyze_consistency MCP tool)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#341` (GC-C007: Consistency Violation Detection)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - Cross-wave and cycle detection (partial consistency))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/ConsistencyViolation.java` (ConsistencyViolation domain record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (GET /api/v1/analysis/consistency-violations endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/ConsistencyViolationResponse.java` (ConsistencyViolationResponse API DTO)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (DetectConsistencyViolations unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (consistencyViolations_detectsActiveConflict integration test)
