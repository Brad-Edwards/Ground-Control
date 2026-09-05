---
id: GC-B007
title: "StrictDoc Export"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-13T23:11:16.337674Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B007 — StrictDoc Export

## Statement

The system shall export documents to StrictDoc (.sdoc) format with lossless round-trip for the common subset of features shared between GC and StrictDoc.

## Rationale

Round-trip capability with StrictDoc enables interoperability and migration. Users should not be locked into GC if they need to return to StrictDoc tooling.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `423` (GC-B007: StrictDoc Export)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentExportSdocService.java` (StrictDoc .sdoc serializer)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentExportService.java` (Document export orchestration service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/export/ExportController.java` (Export REST endpoint for .sdoc)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentExportSdocServiceTest.java` (StrictDoc export tests with round-trip verification)
