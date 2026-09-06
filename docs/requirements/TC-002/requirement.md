---
id: TC-002
title: "Step-Based Test Case Format"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-22T06:13:01.021075Z
updated_at: 2026-05-16T22:55:24.105237Z
---

# TC-002 — Step-Based Test Case Format

## Statement

The system shall support a step-based test case format where each test case contains an ordered sequence of steps, each with: step number, action description, expected result, and actual result fields. Steps shall support rich text and inline images.

## Rationale

Step-based format is the primary test case format in TestRail, Zephyr Scale, Xray, PractiTest, qTest, Azure Test Plans, and TestLink. It enables structured execution with per-step pass/fail tracking.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/041-test-case-step-format.md` (ADR-041: Step-based test case format)
- IMPLEMENTS → GITHUB_ISSUE `670` (TC-002: Step-Based Test Case Format)
- IMPLEMENTS → PULL_REQUEST `914` (security: add step-based test case format for TC-002)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestCaseStep.java` (TestCaseStep entity (TC-002 / ADR-041))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestCaseStepService.java` (TestCaseStepService — CRUD + service-level cascade)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestCaseStepController.java` (TestCaseStepController — /api/v1/test-cases/{id}/steps)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V073__create_test_case_step.sql` (test_case_step table — (test_case_id, step_number) unique, step_number > 0 check)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseStepTest.java` (TestCaseStepTest — entity validation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseStepServiceTest.java` (TestCaseStepServiceTest — service unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestCaseStepControllerTest.java` (TestCaseStepControllerTest — @WebMvcTest with ArgumentCaptors)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TestCaseStepControllerIntegrationTest.java` (TestCaseStepControllerIntegrationTest — HTTP roundtrip + cross-test-case rejection)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TestCaseStepServiceIntegrationTest.java` (TestCaseStepServiceIntegrationTest — Envers ADD/DEL revtype assertions)
