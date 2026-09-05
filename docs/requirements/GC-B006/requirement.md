---
id: GC-B006
title: "StrictDoc Import"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:13.645560Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B006 — StrictDoc Import

## Statement

The system shall import StrictDoc (.sdoc) files, creating documents, sections, text blocks, requirements, and relations, preserving the source hierarchy.

## Rationale

Migration path from StrictDoc. Existing .sdoc specifications must be importable without manual re-entry. Already partially implemented.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `420` (GC-B006: StrictDoc Import)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#325` (Tech debt: ImportService.importStrictdoc() is 150+ lines with three phases in one method)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/ImportService.java` (ImportService - StrictDoc import logic)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/ImportController.java` (ImportController - StrictDoc import API endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/SdocParser.java` (SdocParser - structured document parsing)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SdocParserTest.java` (SdocParser tests including section/text parsing)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ImportServiceTest.java` (ImportService tests including document structure creation)
