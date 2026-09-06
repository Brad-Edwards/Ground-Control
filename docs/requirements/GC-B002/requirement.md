---
id: GC-B002
title: "Hierarchical Sections"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-13T23:11:02.915481Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B002 — Hierarchical Sections

## Statement

The system shall support hierarchical sections within a document, with arbitrary nesting depth, enabling logical grouping and navigation of requirements.

## Rationale

Real-world specifications organize requirements into chapters, sections, and subsections. Flat lists do not scale for large requirement sets.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `403` (GC-B002: Hierarchical Sections)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/model/Section.java` (Section JPA entity with self-referential hierarchy)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/SectionService.java` (SectionService - CRUD and tree building)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/sections/SectionController.java` (SectionController - REST API endpoints)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SectionServiceTest.java` (SectionServiceTest - unit tests including tree building)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/SectionControllerTest.java` (SectionControllerTest - endpoint tests)
