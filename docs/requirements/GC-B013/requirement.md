---
id: GC-B013
title: "ReqIF Import"
status: ACTIVE
type: INTERFACE
priority: COULD
wave: 4
created_at: 2026-03-14T17:23:21.085375Z
updated_at: 2026-03-20T05:22:15.738611Z
---

# GC-B013 — ReqIF Import

## Statement

The system shall import requirements from ReqIF 1.2 format files, creating requirements, relations, and document structure from the ReqIF SpecObjects, SpecRelations, and Specifications. The import shall be idempotent (re-importing the same ReqIF file updates existing requirements rather than creating duplicates) and shall be invokable via both REST API and MCP tools.

## Rationale

ReqIF is the OMG standard for requirements interchange between tools. GC-B010 covers export but not import. Bidirectional ReqIF enables agents to orchestrate requirement migration from enterprise tools (IBM DOORS, Polarion, Jama) into Ground Control and back.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_import_reqif MCP tool)
- IMPLEMENTS → GITHUB_ISSUE `#229` (GC-B013: ReqIF Import)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/ReqifParser.java` (ReqIF 1.2 parser (DOM/JAXP))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/ImportService.java` (ImportService.importReqif() three-phase upsert)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/ImportController.java` (POST /admin/import/reqif REST endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ReqifParserTest.java` (ReqIF parser unit tests (9 tests))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ImportServiceTest.java` (ReqIF import service tests (6 tests in 4 nested classes))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ImportControllerTest.java` (ReqIF import controller tests (3 tests))
