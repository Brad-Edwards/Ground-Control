---
id: GC-P007
title: "Export and Reporting"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-13T23:15:41.956579Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-P007 — Export and Reporting

## Statement

The system shall support exporting requirement data and analysis results to common formats (PDF, Excel, CSV) for reporting, stakeholder communication, and regulatory submissions.

## Rationale

Not all stakeholders use Ground Control directly. Reports in standard formats enable communication with executives, auditors, and external reviewers who need information but not tool access.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `418` (GC-P007: Export and Reporting)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/export/ExportController.java` (Export REST Controller)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementsExportCsvService.java` (Requirements CSV Export Service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementsExportExcelService.java` (Requirements Excel Export Service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementsExportPdfService.java` (Requirements PDF Export Service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/SweepExportCsvService.java` (Sweep CSV Export Service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/SweepExportExcelService.java` (Sweep Excel Export Service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/SweepExportPdfService.java` (Sweep PDF Export Service)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementsExportCsvServiceTest.java` (Requirements CSV Export Tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementsExportExcelServiceTest.java` (Requirements Excel Export Tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementsExportPdfServiceTest.java` (Requirements PDF Export Tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SweepExportCsvServiceTest.java` (Sweep CSV Export Tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SweepExportExcelServiceTest.java` (Sweep Excel Export Tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SweepExportPdfServiceTest.java` (Sweep PDF Export Tests)
