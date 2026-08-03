---
id: GC-A005
title: "No Self-Referential Relations"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:25.356595Z
updated_at: 2026-03-14T22:45:45.422323Z
---

# GC-A005 — No Self-Referential Relations

## Statement

The system shall prevent self-referential relations where the source and target are the same requirement.

## Rationale

A requirement depending on, refining, or parenting itself is semantically invalid and would create trivial cycles in the DAG.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/RequirementRelation.java` (RequirementRelation domain model — self-referential check in constructor)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java` (RequirementService.createRelation — self-referential guard at service layer)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementServiceTest.java` (RequirementServiceTest.throwsDomainValidationForSelfLoop — unit test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementServiceIntegrationTest.java` (RequirementServiceIntegrationTest.selfLoopRelationThrowsDomainValidation — integration test)
- IMPLEMENTS → GITHUB_ISSUE `#290` (GC-A005: No Self-Referential Relations)
