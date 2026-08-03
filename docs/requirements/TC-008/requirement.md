---
id: TC-008
title: "Test Run Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-22T06:13:22.923258Z
updated_at: 2026-05-18T16:29:36.363537Z
---

# TC-008 — Test Run Entity

## Statement

The system shall provide a Test Run entity representing a single execution pass of a test suite, with: unique ID, name, associated test plan, assigned tester(s), environment, build/version, status, start/end timestamps, and individual test case execution results.

## Rationale

Test runs capture execution results against a specific build/environment. All best-of-breed tools distinguish between test cases (what to test) and test runs (the record of testing).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#675` (TC-008: Test Run Entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestRun.java` (TestRun root entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestRunTesterAssignment.java` (TestRunTesterAssignment child entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestRunCaseResult.java` (TestRunCaseResult child entity (snapshot))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestRunService.java` (TestRunService (CRUD, snapshot-on-create, testers, results))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestRunController.java` (TestRunController REST surface /api/v1/test-runs/**)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V110__create_test_run.sql` (TestRun root Flyway migration)
- IMPLEMENTS → ADR `ADR-049` (Test Run Entity)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/testcases/state/TestRunStatusTest.java` (TestRunStatus transition matrix)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/testcases/model/TestRunTest.java` (TestRun entity invariants + status transitions)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/testcases/model/TestRunCaseResultTest.java` (TestRunCaseResult snapshot + status invariants)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/testcases/service/TestRunServiceTest.java` (TestRunService snapshot-on-create + CRUD + delete cascade)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestRunControllerTest.java` (TestRunController @WebMvcTest (CRUD, testers, results endpoints))
- TESTS → TEST `mcp/ground-control/test-run-tools.test.js` (gc_test_run MCP adapter contract tests)
