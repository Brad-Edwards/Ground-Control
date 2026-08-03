---
id: GC-C005
title: "Cross-Wave Validation"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:57.551230Z
updated_at: 2026-03-18T05:41:06.632253Z
---

# GC-C005 — Cross-Wave Validation

## Statement

The system shall validate cross-wave ordering: no requirement in wave N may depend on a requirement in wave M where M is greater than N, as this indicates a build-order violation.

## Rationale

Waves represent implementation phases. If wave 1 depends on wave 3, the build order is incoherent. Cross-wave analysis catches these planning errors early.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - crossWaveValidation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (AnalysisController - cross-wave endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest - crossWaveValidation unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (AnalysisIntegrationTest - cross-wave integration test)
- IMPLEMENTS → GITHUB_ISSUE `338` (GC-C005: Cross-Wave Validation)
