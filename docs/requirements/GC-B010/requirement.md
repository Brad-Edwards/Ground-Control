---
id: GC-B010
title: "ReqIF Export"
status: ACTIVE
type: INTERFACE
priority: COULD
wave: 4
created_at: 2026-03-13T23:11:22.539062Z
updated_at: 2026-03-29T05:04:02.830893Z
---

# GC-B010 — ReqIF Export

## Statement

The system shall export documents to ReqIF 1.2 format for interoperability with industry-standard requirements tools such as IBM DOORS, Polarion, and Jama.

## Rationale

ReqIF is the OMG standard for requirements interchange. Enterprise environments require ReqIF for tool interoperability.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentExportReqifService.java` (ReqIF 1.2 XML export serializer)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentExportReqifServiceTest.java` (ReqIF export tests with round-trip verification)
