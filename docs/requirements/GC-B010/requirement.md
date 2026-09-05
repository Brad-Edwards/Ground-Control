---
id: GC-B010
title: "ReqIF Export"
status: DEPRECATED
type: INTERFACE
priority: COULD
wave: 4
created_at: 2026-03-13T23:11:22.539062Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-B010 — ReqIF Export

## Statement

The system shall export documents to ReqIF 1.2 format for interoperability with industry-standard requirements tools such as IBM DOORS, Polarion, and Jama.

## Rationale

ReqIF is the OMG standard for requirements interchange. Enterprise environments require ReqIF for tool interoperability.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentExportReqifService.java` (ReqIF 1.2 XML export serializer)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentExportReqifServiceTest.java` (ReqIF export tests with round-trip verification)
