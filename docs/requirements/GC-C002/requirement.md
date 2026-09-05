---
id: GC-C002
title: "Completeness Validation"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:11:48.238610Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C002 — Completeness Validation

## Statement

The system shall provide completeness validation identifying requirements missing: statement, rationale, at least one relation, or at least one traceability link.

## Rationale

Incomplete requirements are a leading cause of project failures. Automated completeness checks surface gaps before they become expensive to fix.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#313` (GC-C002: Completeness Validation)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService.completenessValidation())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (GET /api/v1/analysis/completeness endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (CompletenessValidation unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AnalysisControllerTest.java` (CompletenessValidation controller test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (CompletenessValidation integration test)
