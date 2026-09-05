---
id: GC-C005
title: "Cross-Wave Validation"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:57.551230Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C005 — Cross-Wave Validation

## Statement

The system shall validate cross-wave ordering: no requirement in wave N may depend on a requirement in wave M where M is greater than N, as this indicates a build-order violation.

## Rationale

Waves represent implementation phases. If wave 1 depends on wave 3, the build order is incoherent. Cross-wave analysis catches these planning errors early.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `338` (GC-C005: Cross-Wave Validation)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - crossWaveValidation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (AnalysisController - cross-wave endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest - crossWaveValidation unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (AnalysisIntegrationTest - cross-wave integration test)
