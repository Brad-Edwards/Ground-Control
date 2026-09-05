---
id: TC-006
title: "Test Plan Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-22T06:13:16.251741Z
updated_at: 2026-05-17T21:49:22.036868Z
---

# TC-006 — Test Plan Entity

## Statement

The system shall provide a Test Plan entity with: unique ID, name, description, associated product/version/build, status, start/end dates, and the ability to group multiple test runs under a single plan.

## Rationale

Test plans are the top-level organizational container in all best-of-breed tools. They define the scope, schedule, and strategy for a testing effort.

## Traceability

- IMPLEMENTS → PULL_REQUEST `#921` (feat(tc-006): add TestPlan entity for top-level test effort planning)
- IMPLEMENTS → ADR `architecture/adrs/044-test-plan-entity.md` (ADR-044: Test Plan Entity)
- IMPLEMENTS → GITHUB_ISSUE `#673` (TC-006: Test Plan Entity)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestPlan.java` (TestPlan entity (@Audited domain aggregate))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/state/TestPlanStatus.java` (TestPlanStatus enum + transition matrix)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestPlanService.java` (TestPlanService (CRUD + status transition + partial-update contract))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/repository/TestPlanRepository.java` (TestPlanRepository (Spring Data JPA))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestPlanController.java` (TestPlanController REST surface (/api/v1/test-plans))
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V088__create_test_plan.sql` (V088 Flyway migration: test_plan table)
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V089__create_test_plan_audit.sql` (V089 Flyway migration: test_plan_audit Envers shadow)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestPlanStatusTest.java` (TestPlanStatusTest (transition matrix))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestPlanTest.java` (TestPlanTest (entity invariants + schedule rules))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestPlanServiceTest.java` (TestPlanServiceTest (business logic + partial-update + schedule shifts))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestPlanControllerTest.java` (TestPlanControllerTest (@WebMvcTest + ArgumentCaptor body binding))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TestPlanControllerIntegrationTest.java` (TestPlanControllerIntegrationTest (end-to-end Postgres CRUD + lifecycle))
