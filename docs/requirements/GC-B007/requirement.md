---
id: GC-B007
title: "StrictDoc Export"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-13T23:11:16.337674Z
updated_at: 2026-03-28T23:41:50.688345Z
---

# GC-B007 — StrictDoc Export

## Statement

The system shall export documents to StrictDoc (.sdoc) format with lossless round-trip for the common subset of features shared between GC and StrictDoc.

## Rationale

Round-trip capability with StrictDoc enables interoperability and migration. Users should not be locked into GC if they need to return to StrictDoc tooling.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `423` (GC-B007: StrictDoc Export)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentExportSdocService.java` (StrictDoc .sdoc serializer)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentExportService.java` (Document export orchestration service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/export/ExportController.java` (Export REST endpoint for .sdoc)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentExportSdocServiceTest.java` (StrictDoc export tests with round-trip verification)
