---
id: GC-L001
title: "MCP Requirements CRUD"
status: ACTIVE
type: INTERFACE
priority: MUST
wave: 1
created_at: 2026-03-13T23:14:41.039404Z
updated_at: 2026-03-18T07:22:42.355057Z
---

# GC-L001 — MCP Requirements CRUD

## Statement

The system shall expose full requirements CRUD operations (create, read, update, list, archive) as MCP tools, enabling AI agents to manage requirements without REST API knowledge.

## Rationale

MCP is the primary interface for AI agents. Requirements management must be fully accessible via MCP tools to enable agent-driven development workflows.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP Server - Requirements CRUD tools)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib — REST API client functions for requirements CRUD)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/RequirementController.java` (RequirementController — REST endpoints backing MCP CRUD tools)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementControllerIntegrationTest.java` (Requirements REST CRUD integration tests (create/get/getByUid/update/transition/archive) backing the MCP requirements tools)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#346` (GC-L001: MCP Requirements CRUD)
