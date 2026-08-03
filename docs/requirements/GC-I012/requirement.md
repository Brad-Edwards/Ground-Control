---
id: GC-I012
title: "Control Testing Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T19:33:06.735189Z
updated_at: 2026-05-13T05:09:10.892195Z
---

# GC-I012 — Control Testing Entity

## Statement

The system shall support a control test entity with the control or scoped control implementation being tested, tested asset scope when applicable, test methodology, test steps, expected and actual results, test conclusion, tester identity, test date, and explicit indication of which assessment dimensions or FAIR factors the test provides evidence about. Test results shall be stored as evidence artifacts linked to the control, relevant observations, and, when applicable, to relevant risk scenarios or risk records.

## Rationale

Control tests should not merely say whether a control passed or failed; they should provide evidence about how a control influences assessed risk in a particular operational context. That evidence must be reusable across qualitative and quantitative methodologies.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/model/ControlTest.java` (ControlTest entity (audited evidence row))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlTestService.java` (ControlTestService (project-scoped CRUD + delete guard))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/controls/ControlTestController.java` (ControlTestController (/api/v1/control-tests REST))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/state/ControlTestMethodology.java` (ControlTestMethodology enum (PCAOB AS 2201 vocabulary))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/state/ControlTestConclusion.java` (ControlTestConclusion enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V065__create_control_test.sql` (V065 control_test schema (Flyway migration))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ControlTestGraphProjectionContributor.java` (ControlTest graph projection contributor (OF_CONTROL edges))
- IMPLEMENTS → ADR `architecture/adrs/039-control-verification-subsystem.md` (ADR-039 Control Verification Subsystem)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlTestServiceTest.java` (ControlTestService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ControlTestControllerTest.java` (ControlTestController @WebMvcTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/ControlTestControllerIntegrationTest.java` (ControlTestController integration test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlTestGraphProjectionContributorTest.java` (ControlTest graph projection contributor unit test)
- IMPLEMENTS → GITHUB_ISSUE `#270` (GC-I012: Control Testing Entity)
