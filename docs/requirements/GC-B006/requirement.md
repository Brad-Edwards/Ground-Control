---
id: GC-B006
title: "StrictDoc Import"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:13.645560Z
updated_at: 2026-03-28T21:59:53.971474Z
---

# GC-B006 — StrictDoc Import

## Statement

The system shall import StrictDoc (.sdoc) files, creating documents, sections, text blocks, requirements, and relations, preserving the source hierarchy.

## Rationale

Migration path from StrictDoc. Existing .sdoc specifications must be importable without manual re-entry. Already partially implemented.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/ImportService.java` (ImportService - StrictDoc import logic)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/ImportController.java` (ImportController - StrictDoc import API endpoint)
- IMPLEMENTS → GITHUB_ISSUE `420` (GC-B006: StrictDoc Import)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/SdocParser.java` (SdocParser - structured document parsing)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SdocParserTest.java` (SdocParser tests including section/text parsing)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ImportServiceTest.java` (ImportService tests including document structure creation)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#325` (Tech debt: ImportService.importStrictdoc() is 150+ lines with three phases in one method)
