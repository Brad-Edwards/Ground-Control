---
id: GC-D007
title: "Issue Creation from Requirement"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 1
created_at: 2026-03-14T03:23:41.746444Z
updated_at: 2026-03-14T03:23:41.746444Z
---

# GC-D007 — Issue Creation from Requirement

## Statement

The system shall support creating a GitHub issue from a requirement via MCP tool, populating the issue title with the requirement UID and title, the issue body with the requirement's statement, rationale, type, priority, wave, and status, and automatically creating a DOCUMENTS traceability link from the requirement to the newly created issue.

## Rationale

Creating GitHub issues from requirements is a repeated workflow during wave activation. Manual issue creation requires copying requirement fields, formatting markdown, creating the issue, then manually linking it back — error-prone and tedious. A single MCP tool call eliminates the ceremony and ensures every issue is linked from the moment of creation.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#191` (GC-D007: Issue Creation from Requirement — Implement MCP tool)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GitHubIssueSyncService.java` (GitHubIssueSyncService - Issue creation from requirement)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GitHubIssueController.java` (GitHubIssueController - Issue creation API endpoint)
