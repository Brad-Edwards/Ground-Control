---
id: GC-A012
title: "Dual API Exposure"
status: ACTIVE
type: INTERFACE
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:41.925297Z
updated_at: 2026-03-15T19:20:34.624215Z
---

# GC-A012 — Dual API Exposure

## Statement

The system shall expose all requirements operations via both REST API and MCP tools, ensuring feature parity between human and agent interfaces.

## Rationale

MCP is the primary interface for AI agents. REST is the primary interface for UIs and integrations. Both interfaces must provide equivalent capabilities to avoid second-class citizens.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP helper functions (9 new API wrappers + createGitHubIssueViaApi))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool registrations (9 new tools + migrated gc_create_github_issue))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GitHubIssueController.java` (REST endpoint POST /api/v1/admin/github/issues)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GitHubIssueSyncService.java` (createIssueFromRequirement() domain service method)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/github/GitHubCliClient.java` (GitHubCliClient.createIssue() implementation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GitHubIssueControllerTest.java` (GitHubIssueController WebMvc tests (201 + validation))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GitHubIssueSyncServiceTest.java` (GitHubIssueSyncService.IssueCreation test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/GitHubCliClientTest.java` (GitHubCliClient.CreateIssueUrlParsing test)
- DOCUMENTS → DOCUMENTATION `docs/API.md` (API docs — GitHub Issues section)
- IMPLEMENTS → GITHUB_ISSUE `#305` (GC-A012: Dual API Exposure)
- IMPLEMENTS → GITHUB_ISSUE `#251` (Requirement update API contract is inconsistent with implementation and MCP client)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#314` (Bug: Traceability link/relation endpoints ignore parent requirement ID)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#315` (Bug: GlobalExceptionHandler missing catch-all for non-domain exceptions)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#316` (Bug: GraphController has no class-level @RequestMapping and bypasses service layer)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#324` (Tech debt: Untyped Map<String, Object> in API error responses)
