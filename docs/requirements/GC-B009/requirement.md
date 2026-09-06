---
id: GC-B009
title: "PDF Export"
status: DEPRECATED
type: FUNCTIONAL
priority: COULD
wave: 4
created_at: 2026-03-13T23:11:20.247761Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B009 — PDF Export

## Statement

The system shall export documents to PDF format for formal distribution and archival.

## Rationale

PDF is the standard format for formal document distribution, regulatory submissions, and long-term archival.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `427` (GC-B009: PDF Export)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentExportPdfService.java` (PDF document export serializer)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentExportPdfServiceTest.java` (PDF document export tests)
