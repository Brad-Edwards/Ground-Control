---
id: TC-009
title: "Manual Test Execution Runner"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-22T06:13:26.491772Z
updated_at: 2026-05-19T03:06:31.594656Z
---

# TC-009 — Manual Test Execution Runner

## Statement

The system shall provide a browser-based test execution runner supporting: step-by-step execution with pass/fail/blocked/skip per step, overall test result status, pause and resume, comments/notes per step and per test, and execution timestamps.

## Rationale

Every best-of-breed tool provides a dedicated execution UI. Azure Test Plans and TestRail provide both browser-based and desktop runners. The execution runner is the primary workflow for manual testers.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#676` (TC-009: Manual Test Execution Runner)
- IMPLEMENTS → PULL_REQUEST `#932` (feat: add browser-based test execution runner (TC-009))
- IMPLEMENTS → ADR `architecture/adrs/050-manual-test-execution-step-result.md` (ADR-050: Manual Test Execution Step Result)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestRunStepResult.java` (TestRunStepResult entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestRunService.java` (TestRunService (TC-009 extensions))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestRunController.java` (TestRunController (TC-009 endpoints))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V116__create_test_run_step_result.sql` (V116 create test_run_step_result)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V117__create_test_run_step_result_audit.sql` (V117 create test_run_step_result_audit)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V118__add_test_run_cursor.sql` (V118 add test_run cursor)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/test-run-runner.tsx` (Runner page (frontend))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_test_run MCP runner actions)
- DOCUMENTS → DOCUMENTATION `docs/API.md` (API.md runner endpoint documentation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/testcases/model/TestRunStepResultTest.java` (TestRunStepResultTest (entity invariants))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/testcases/service/TestRunServiceTest.java` (TestRunServiceTest (TC-009 sections))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestRunControllerTest.java` (TestRunControllerTest (TC-009 endpoints))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java` (MigrationSmokeTest (V116-V118 schema pins))
- TESTS → TEST `mcp/ground-control/test-run-tools.test.js` (MCP test-run-tools tests (runner section))
- TESTS → TEST `frontend/src/pages/__tests__/test-run-runner.test.tsx` (Runner page tests: per-case notes and per-step comment draft resets, cursor persistence for pause/resume)
