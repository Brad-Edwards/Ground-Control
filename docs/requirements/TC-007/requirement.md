---
id: TC-007
title: "Test Suite Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-22T06:13:19.358511Z
updated_at: 2026-05-18T02:32:18.694160Z
---

# TC-007 — Test Suite Entity

## Statement

The system shall provide a Test Suite entity supporting three population modes: static (manually selected test cases), requirements-based (auto-populated from linked requirements), and query-based (auto-populated from filter criteria with dynamic updates as matching cases change).

## Rationale

Azure Test Plans provides all three modes. Static suites give manual control, requirements-based suites ensure coverage, query-based suites enable dynamic test selection as the repository evolves.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#674` (TC-007: Test Suite Entity)
- IMPLEMENTS → PULL_REQUEST `#924` (feat(testcases): add TestSuite entity with three population modes (TC-007))
- IMPLEMENTS → ADR `ADR-047` (ADR-047: Test Suite Entity)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestSuite.java` (TestSuite entity (aggregate root with population_mode + criteria columns))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestSuiteService.java` (TestSuiteService (CRUD + per-mode population ops + mode-dispatch resolve))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestSuiteController.java` (TestSuiteController (REST surface under /api/v1/test-suites))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V094__create_test_suite.sql` (V094 test_suite root table (population_mode CHECK constraint, criteria columns))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestSuiteTest.java` (TestSuiteTest (entity constructor / mode immutability / criteria field invariants))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestSuiteServiceTest.java` (TestSuiteServiceTest (CRUD per mode, member/source ops, per-mode resolve dispatch))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestSuiteControllerTest.java` (TestSuiteControllerTest (@WebMvcTest controller wiring + body-binding captors))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TestSuiteControllerIntegrationTest.java` (TestSuiteControllerIntegrationTest (end-to-end all 3 modes via PostgreSQL Testcontainers))
