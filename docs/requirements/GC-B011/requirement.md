---
id: GC-B011
title: "Requirement-Section Membership"
status: DEPRECATED
type: CONSTRAINT
priority: MUST
wave: 2
created_at: 2026-03-13T23:11:25.663528Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B011 — Requirement-Section Membership

## Statement

A requirement shall belong to at most one document section. A requirement may exist outside any document as a standalone entity.

## Rationale

Allowing a requirement in multiple sections creates ambiguity about its canonical location. Standalone requirements support ad-hoc creation before organization.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `413` (GC-B011: Requirement-Section Membership)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/SectionContentService.java` (SectionContentService - at-most-one-section validation)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V022__unique_requirement_per_section.sql` (Partial unique index enforcing at-most-one section per requirement)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SectionContentServiceTest.java` (SectionContentServiceTest - rejects requirement already in section)
