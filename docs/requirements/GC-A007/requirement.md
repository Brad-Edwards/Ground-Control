---
id: GC-A007
title: "Requirement Cloning"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:10:30.690791Z
updated_at: 2026-03-15T01:29:21.071986Z
---

# GC-A007 — Requirement Cloning

## Statement

The system shall support cloning a requirement, creating a new requirement with a new UID and optionally copying its relations.

## Rationale

Creating variants of existing requirements is common when requirements fork or when similar requirements apply to different contexts. Manual re-entry is error-prone.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java` (RequirementService.clone())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (POST /api/v1/requirements/{id}/clone endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java` (RequirementServiceTest.Clone)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (RequirementControllerTest.Clone)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementControllerIntegrationTest.java` (RequirementControllerIntegrationTest.cloneRequirement)
- IMPLEMENTS → GITHUB_ISSUE `#294` (GC-A007: Requirement Cloning)
