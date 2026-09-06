---
id: TC-001
title: "Test Case Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-22T06:12:59.203113Z
updated_at: 2026-05-16T06:47:35.587460Z
---

# TC-001 — Test Case Entity

## Statement

The system shall provide a Test Case entity with: unique ID, title, description (rich text), preconditions, postconditions, priority, status lifecycle (Draft/Approved/Deprecated/Archived), type classification (Manual/Automated/Hybrid), estimated duration, and project scoping.

## Rationale

Core entity for test management. Every best-of-breed tool (TestRail, Zephyr Scale, Xray, Azure Test Plans, PractiTest, qTest, Kiwi TCMS, TestLink) provides a dedicated test case entity as the foundation of their test management domain.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/040-test-case-domain.md` (ADR-040 — Test case domain boundary)
- DOCUMENTS → GITHUB_ISSUE `#669` (TC-001: Test Case Entity)
- IMPLEMENTS → PULL_REQUEST `913` (PR #913 — feat(testcases): add TestCase aggregate (TC-001))

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestCase.java` (TestCase entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/state/TestCaseStatus.java` (TestCaseStatus lifecycle enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/state/TestCaseType.java` (TestCaseType classification enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/state/TestCasePriority.java` (TestCasePriority severity enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestCaseService.java` (TestCaseService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestCaseController.java` (TestCaseController REST surface)
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V069__create_test_case.sql` (V069 test_case table migration)
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V070__create_test_case_audit.sql` (V070 test_case_audit table migration)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseStatusTest.java` (TestCaseStatusTest — lifecycle matrix)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseTest.java` (TestCaseTest — entity invariants)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseServiceTest.java` (TestCaseServiceTest — CRUD + clear-flag semantics)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestCaseControllerTest.java` (TestCaseControllerTest — @WebMvcTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TestCaseControllerIntegrationTest.java` (TestCaseControllerIntegrationTest — full round-trip)
- TESTS → TEST `mcp/ground-control/test-case-tools.test.js` (MCP gc_test_case adapter tests)
