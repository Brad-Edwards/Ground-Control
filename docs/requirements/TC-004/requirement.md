---
id: TC-004
title: "BDD/Gherkin Test Case Format"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 1
created_at: 2026-03-22T06:13:05.962353Z
updated_at: 2026-05-17T06:56:33.011434Z
---

# TC-004 — BDD/Gherkin Test Case Format

## Statement

The system shall support BDD/Gherkin test case format with Given/When/Then syntax, Scenario and Scenario Outline support, and Examples tables for parameterized scenarios.

## Rationale

Supported by Xray, Zephyr Scale, PractiTest, and qTest Scenario. BDD format bridges requirements and tests, enabling behavior-driven development workflows.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/042-test-case-bdd-gherkin-format.md` (ADR-042: BDD/Gherkin authored format for test cases)
- IMPLEMENTS → GITHUB_ISSUE `671` (TC-004: BDD/Gherkin Test Case Format)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/state/TestCaseFormat.java` (TestCaseFormat enum (STEP_BASED, GHERKIN))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestCaseGherkin.java` (TestCaseGherkin entity (one-per-parent Gherkin source))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/GherkinValidator.java` (GherkinValidator (parser + size/scenario/examples caps))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestCaseGherkinService.java` (TestCaseGherkinService (CRUD + format gate + cascade))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestCaseGherkinController.java` (TestCaseGherkinController (REST surface at /api/v1/test-cases/{id}/gherkin))
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V076__add_test_case_format.sql` (V076 — add test_case.format discriminator)
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V078__create_test_case_gherkin.sql` (V078 — create test_case_gherkin table (UNIQUE on test_case_id))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GherkinValidatorTest.java` (GherkinValidatorTest — clause-by-clause Given/When/Then + Scenario + Outline + Examples coverage)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseGherkinServiceTest.java` (TestCaseGherkinServiceTest — service CRUD + format gate + cascade)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseGherkinTest.java` (TestCaseGherkinTest — entity invariants)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestCaseGherkinControllerTest.java` (TestCaseGherkinControllerTest — @WebMvcTest controller slice)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseFormatTest.java` (TestCaseFormatTest — enum shape)
- TESTS → TEST `mcp/ground-control/test-case-tools.test.js` (MCP test-case adapter tests — gc_test_case gherkin-* actions + TEST_CASE_FORMATS enum mirror)
