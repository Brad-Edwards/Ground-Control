---
id: GC-N004
title: "Structured Baseline Diff"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T17:23:29.919641Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-N004 — Structured Baseline Diff

## Statement

The system shall provide a structured diff API that returns machine-readable change sets between two named baselines, identifying: requirements added, requirements removed, requirements modified (with per-field diffs), relations added/removed, and traceability links added/removed. The API shall be exposed via both REST and MCP tools.

## Rationale

GC-N002 supports named baselines but provides no diff capability. Agents and auditors need to answer "what changed between release 1.0 and 2.0?" programmatically. Baseline diffs build on per-requirement version diffs (GC-N003) and baseline snapshots (GC-N002) to provide release-level change visibility.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#231` (GC-N004: Structured Baseline Diff)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `domain/baselines/service/BaselineService.java#compare` (Baseline compare — structured diff of added/removed/modified)
- IMPLEMENTS → CODE_FILE `api/baselines/BaselineComparisonResponse.java` (Structured comparison response with counts and detail)
- TESTS → TEST `unit/api/BaselineControllerTest.java#Compare` (Baseline comparison endpoint test)
