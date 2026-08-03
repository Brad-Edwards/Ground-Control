---
id: GC-C009
title: "Batch Validation"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:12:08.758006Z
updated_at: 2026-03-24T05:02:19.489587Z
---

# GC-C009 — Batch Validation

## Statement

The system shall support running all validations as a batch operation, returning a consolidated report covering cycles, orphans, coverage gaps, cross-wave violations, and consistency issues.

## Rationale

Running individual validations one at a time is inefficient. A consolidated health check gives an at-a-glance view of specification quality.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - Individual validations (partial batch))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisSweepService.java` (AnalysisSweepService - Batch validation orchestrator)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/SweepReport.java` (SweepReport - Consolidated validation report)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/SweepController.java` (SweepController - Batch validation REST endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/SweepReportResponse.java` (SweepReportResponse - Consolidated report API DTO)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisSweepServiceTest.java` (AnalysisSweepServiceTest - Batch validation unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/SweepControllerTest.java` (SweepControllerTest - Batch validation endpoint tests)
- DOCUMENTS → GITHUB_ISSUE `396` (GC-C009: Batch Validation)
