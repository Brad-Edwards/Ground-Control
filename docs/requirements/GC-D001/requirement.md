---
id: GC-D001
title: "Bidirectional Issue Sync"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:12:33.718791Z
updated_at: 2026-04-05T01:08:30.895537Z
---

# GC-D001 — Bidirectional Issue Sync

## Statement

The system shall support bidirectional synchronization between requirements and GitHub issues: creating or updating GitHub issues from requirements, and reflecting issue state changes back into the requirement.

## Rationale

GitHub issues are the primary work-tracking mechanism for developers. Bidirectional sync ensures requirements and implementation work stay aligned without manual reconciliation.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tools gc_sync_github and gc_create_github_issue with Zod regex validation)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (createGitHubIssue with repo format validation)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#317` (Bug: GitHubCliClient silently truncates issue list at 500)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GitHubIssueSyncService.java` (GitHubIssueSyncService - Bidirectional issue sync)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/SyncController.java` (SyncController - Sync API endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/github/GitHubCliClient.java` (GitHubCliClient with owner/repo/title/body/label input validation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/GlobalExceptionHandler.java` (ConstraintViolationException handler for @Validated parameter validation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/GitHubCliClientTest.java` (Input validation tests for command injection prevention)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/SyncControllerTest.java` (SyncController validation rejection tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GitHubIssueSyncServiceTest.java` (Traceability link state reflection and safe state parsing tests)
