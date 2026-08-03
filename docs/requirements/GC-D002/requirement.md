---
id: GC-D002
title: "PR-Requirement Linking"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-13T23:12:36.616845Z
updated_at: 2026-04-05T19:59:36.295979Z
---

# GC-D002 — PR-Requirement Linking

## Statement

The system shall support linking pull requests to requirements, tracking which PRs implement which requirements and reflecting PR state (open, merged, closed) in the traceability graph.

## Rationale

PRs are the unit of code delivery. Linking PRs to requirements closes the loop between what was specified and what was implemented, enabling implementation coverage tracking.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/PullRequestState.java` (PullRequestState enum (OPEN, CLOSED, MERGED))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/ArtifactType.java` (ArtifactType enum (added PULL_REQUEST))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/GitHubPullRequestSync.java` (GitHubPullRequestSync entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/GitHubPullRequestSyncRepository.java` (GitHubPullRequestSyncRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GitHubPullRequestData.java` (GitHubPullRequestData record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/PrSyncResult.java` (PrSyncResult record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GitHubIssueSyncService.java` (GitHubIssueSyncService (PR sync methods))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GitHubClient.java` (GitHubClient interface (added fetchAllPullRequests))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/github/GitHubCliClient.java` (GitHubCliClient (PR fetching and parsing))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/SyncController.java` (SyncController (PR sync endpoint))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/PrSyncResultResponse.java` (PrSyncResultResponse DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V048__create_github_pr_sync.sql` (V048 migration: github_pr_sync table)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool: gc_sync_github_prs)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib: syncGithubPrs + PULL_REQUEST artifact type)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GitHubPullRequestSyncTest.java` (GitHubPullRequestSync entity unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GitHubIssueSyncServiceTest.java` (GitHubIssueSyncService PR sync tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/GitHubCliClientPrTest.java` (GitHubCliClient PR parsing tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/SyncControllerTest.java` (SyncController PR endpoint WebMvcTest)
