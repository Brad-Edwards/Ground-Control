---
id: GC-B003
title: "Content Ordering"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-13T23:11:05.544652Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B003 — Content Ordering

## Statement

The system shall support ordering of sections and content within sections, ensuring requirements and text blocks have a defined sequence for rendering in reading order.

## Rationale

Document structure requires deterministic ordering. Without explicit ordering, rendered documents would have arbitrary or unstable content sequences.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `405` (GC-B003: Content Ordering)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/model/SectionContent.java` (SectionContent entity - ordered content within sections)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/SectionContentService.java` (SectionContentService - CRUD with type validation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SectionContentServiceTest.java` (SectionContentServiceTest - content ordering and type validation tests)
