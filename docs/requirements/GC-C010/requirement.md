---
id: GC-C010
title: "Configurable Quality Gates"
status: DEPRECATED
type: FUNCTIONAL
priority: COULD
wave: 3
created_at: 2026-03-13T23:12:11.662610Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C010 — Configurable Quality Gates

## Statement

The system shall support configurable validation thresholds as quality gates (for example, minimum 80% of ACTIVE requirements must have a tests link), with pass/fail results for CI/CD integration.

## Rationale

Quality gates enable organizations to enforce minimum standards for specification quality. Threshold-based gating is more practical than requiring 100% coverage immediately.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `399` (GC-C010: Configurable Quality Gates)
- IMPLEMENTS → PULL_REQUEST `496` ([codex] Enforce ADR conformance across repo tooling)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/qualitygates/service/QualityGateService.java` (QualityGateService - CRUD and evaluation logic)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/qualitygates/QualityGateController.java` (QualityGateController - REST API endpoints)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/QualityGateServiceTest.java` (QualityGateServiceTest - unit tests for quality gate service)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/QualityGateControllerTest.java` (QualityGateControllerTest - controller endpoint tests)
