---
id: GC-B012
title: "Document Reading Order View"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-13T23:11:28.283001Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B012 — Document Reading Order View

## Statement

The system shall support viewing a document in reading order: sections, text blocks, and requirements rendered in their authored sequence, not as a flat requirement list.

## Rationale

Documents are meant to be read. A reading-order view is the primary way humans consume specifications. Without it, documents are just lists with extra metadata.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `407` (GC-B012: Document Reading Order View)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentReadingOrderService.java` (DocumentReadingOrderService - composite reading order view)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentReadingOrderServiceTest.java` (DocumentReadingOrderServiceTest - reading order with nested sections and content)
