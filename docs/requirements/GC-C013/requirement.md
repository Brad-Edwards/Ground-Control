---
id: GC-C013
title: "Scheduled Analysis Sweeps"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-14T01:24:26.127064Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C013 — Scheduled Analysis Sweeps

## Statement

The system shall support scheduled execution of the full analysis suite (orphan detection, coverage gaps, cross-wave validation, cycle detection, consistency checks) with the ability to emit results as GitHub issues or webhook notifications for detected problems.

## Rationale

CI/CD gates catch problems at PR time, but traceability can also degrade through external changes (deleted branches, closed issues, refactored files). Scheduled sweeps serve as the safety net that catches drift between PRs, ensuring no problem persists undetected for more than the sweep interval.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- DOCUMENTS → GITHUB_ISSUE `360` ([GC-C013] Scheduled Analysis Sweeps)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/sweep/WebhookSweepNotifier.java` (WebhookSweepNotifier — sends sweep results via webhook POST)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisSweepServiceTest.java` (AnalysisSweepServiceTest — verifies sweep orchestration and notification)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/SweepControllerTest.java` (SweepControllerTest — verifies REST API sweep endpoints)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/sweep/GitHubIssueSweepNotifierTest.java` (GitHubIssueSweepNotifierTest — verifies markdown report formatting)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisSweepService.java` (AnalysisSweepService — orchestrates sweep execution and notifications)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/sweep/ScheduledSweepRunner.java` (ScheduledSweepRunner — cron-based scheduled sweep trigger)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/sweep/GitHubIssueSweepNotifier.java` (GitHubIssueSweepNotifier — creates GitHub issues for sweep problems)
