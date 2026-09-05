---
id: GC-B008
title: "HTML Export"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-13T23:11:18.471404Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B008 — HTML Export

## Statement

The system shall export documents to HTML format for web publishing and review.

## Rationale

HTML is the universal format for web-based review and publishing. Stakeholders who don't use GC directly need a way to read specifications.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `425` (GC-B008: HTML Export)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentExportHtmlService.java` (HTML document export serializer)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentExportHtmlServiceTest.java` (HTML export tests with XSS escaping verification)
