---
id: GC-A005
title: "No Self-Referential Relations"
status: DEPRECATED
type: CONSTRAINT
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:25.356595Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-A005 — No Self-Referential Relations

## Statement

The system shall prevent self-referential relations where the source and target are the same requirement.

## Rationale

A requirement depending on, refining, or parenting itself is semantically invalid and would create trivial cycles in the DAG.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#290` (GC-A005: No Self-Referential Relations)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/RequirementRelation.java` (RequirementRelation domain model — self-referential check in constructor)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java` (RequirementService.createRelation — self-referential guard at service layer)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementServiceTest.java` (RequirementServiceTest.throwsDomainValidationForSelfLoop — unit test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementServiceIntegrationTest.java` (RequirementServiceIntegrationTest.selfLoopRelationThrowsDomainValidation — integration test)
