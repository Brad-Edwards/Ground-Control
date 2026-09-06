---
id: GC-N002
title: "Baseline Management"
status: ACTIVE
type: FUNCTIONAL
priority: COULD
wave: 4
created_at: 2026-03-13T23:15:06.079311Z
updated_at: 2026-03-20T02:35:39.885454Z
---

# GC-N002 — Baseline Management

## Statement

The system shall support creating named baselines — point-in-time snapshots of the requirement set — enabling comparison between baselines and tracking specification evolution over releases.

## Rationale

Baselines are essential for release management. Comparing baselines shows what changed between releases, supporting release notes, audit trails, and regression analysis.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP baseline tools (6 tools))
- DOCUMENTS → GITHUB_ISSUE `354` (GC-N002: Baseline Management)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `domain/baselines/service/BaselineService.java` (Baseline service — create, snapshot, compare, delete)
- IMPLEMENTS → CODE_FILE `api/baselines/BaselineController.java` (Baseline REST API controller)
- TESTS → TEST `unit/domain/BaselineServiceTest.java` (Baseline service unit tests)
- TESTS → TEST `unit/api/BaselineControllerTest.java` (Baseline controller unit tests)
