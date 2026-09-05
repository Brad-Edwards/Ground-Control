---
id: GC-C009
title: "Batch Validation"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:12:08.758006Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C009 — Batch Validation

## Statement

The system shall support running all validations as a batch operation, returning a consolidated report covering cycles, orphans, coverage gaps, cross-wave violations, and consistency issues.

## Rationale

Running individual validations one at a time is inefficient. A consolidated health check gives an at-a-glance view of specification quality.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- DOCUMENTS → GITHUB_ISSUE `396` (GC-C009: Batch Validation)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - Individual validations (partial batch))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisSweepService.java` (AnalysisSweepService - Batch validation orchestrator)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/SweepReport.java` (SweepReport - Consolidated validation report)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/SweepController.java` (SweepController - Batch validation REST endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/SweepReportResponse.java` (SweepReportResponse - Consolidated report API DTO)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisSweepServiceTest.java` (AnalysisSweepServiceTest - Batch validation unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/SweepControllerTest.java` (SweepControllerTest - Batch validation endpoint tests)
