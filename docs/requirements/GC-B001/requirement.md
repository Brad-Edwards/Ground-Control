---
id: GC-B001
title: "Document Entity"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-13T23:11:00.661030Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B001 — Document Entity

## Statement

The system shall support a Document entity with title, version, and metadata, serving as the top-level container for organized collections of requirements.

## Rationale

Requirements need organizational context beyond flat lists. Documents group requirements into coherent specifications, replacing StrictDoc's .sdoc files as the native authoring structure.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `401` (GC-B001: Document Entity)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentService.java` (DocumentService - CRUD logic)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/model/Document.java` (Document JPA entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/documents/DocumentController.java` (DocumentController - REST API endpoints)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentServiceTest.java` (DocumentServiceTest - unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/DocumentControllerTest.java` (DocumentControllerTest - endpoint tests)
