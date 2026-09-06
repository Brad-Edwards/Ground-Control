---
id: GC-A007
title: "Requirement Cloning"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:10:30.690791Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-A007 — Requirement Cloning

## Statement

The system shall support cloning a requirement, creating a new requirement with a new UID and optionally copying its relations.

## Rationale

Creating variants of existing requirements is common when requirements fork or when similar requirements apply to different contexts. Manual re-entry is error-prone.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#294` (GC-A007: Requirement Cloning)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java` (RequirementService.clone())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (POST /api/v1/requirements/{id}/clone endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java` (RequirementServiceTest.Clone)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (RequirementControllerTest.Clone)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementControllerIntegrationTest.java` (RequirementControllerIntegrationTest.cloneRequirement)
