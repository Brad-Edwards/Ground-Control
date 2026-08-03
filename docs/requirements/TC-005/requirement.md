---
id: TC-005
title: "Hierarchical Test Case Organization"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-22T06:13:08.038875Z
updated_at: 2026-05-17T19:13:00.785952Z
---

# TC-005 — Hierarchical Test Case Organization

## Statement

The system shall support hierarchical folder/section organization for test cases with unlimited nesting, drag-and-drop reordering, move/copy between folders, and tree-based repository browsing.

## Rationale

All best-of-breed tools provide hierarchical organization. Tree-based browsing is the primary navigation model for test repositories at scale.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestCaseFolder.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestCaseFolderService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestCaseFolderController.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestCaseTreeController.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/testcases/TestCaseController.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/TestCaseService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/TestCase.java`
- IMPLEMENTS → ADR `ADR-043` (Test Case Hierarchical Organization)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseFolderTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseFolderServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestCaseFolderControllerTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestCaseTreeControllerTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TestCaseFolderControllerIntegrationTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TestCaseControllerIntegrationTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TestCaseControllerTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TestCaseServiceTest.java`
- IMPLEMENTS → GITHUB_ISSUE `#672` (TC-005: Hierarchical Test Case Organization)
