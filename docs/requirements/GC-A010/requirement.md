---
id: GC-A010
title: "Paginated Listing"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:37.363513Z
updated_at: 2026-03-18T04:20:55.810694Z
---

# GC-A010 — Paginated Listing

## Statement

The system shall support paginated listing of requirements with configurable page size and sort order.

## Rationale

Unbounded result sets degrade performance and usability. Pagination is essential for any list endpoint.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool: gc_list_requirements sort parameter)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib: listRequirements sort parameter)
- IMPLEMENTS → GITHUB_ISSUE `333` (GC-A010: Add sort parameter to gc_list_requirements MCP tool)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (RequirementController - Paginated listing endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementControllerIntegrationTest.java` (listRequirements_returns200WithPagination verifies the paginated list response shape (content/totalElements))
