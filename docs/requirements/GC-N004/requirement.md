---
id: GC-N004
title: "Structured Baseline Diff"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T17:23:29.919641Z
updated_at: 2026-03-20T02:35:40.419019Z
---

# GC-N004 — Structured Baseline Diff

## Statement

The system shall provide a structured diff API that returns machine-readable change sets between two named baselines, identifying: requirements added, requirements removed, requirements modified (with per-field diffs), relations added/removed, and traceability links added/removed. The API shall be exposed via both REST and MCP tools.

## Rationale

GC-N002 supports named baselines but provides no diff capability. Agents and auditors need to answer "what changed between release 1.0 and 2.0?" programmatically. Baseline diffs build on per-requirement version diffs (GC-N003) and baseline snapshots (GC-N002) to provide release-level change visibility.

## Traceability

- IMPLEMENTS → CODE_FILE `domain/baselines/service/BaselineService.java#compare` (Baseline compare — structured diff of added/removed/modified)
- IMPLEMENTS → CODE_FILE `api/baselines/BaselineComparisonResponse.java` (Structured comparison response with counts and detail)
- TESTS → TEST `unit/api/BaselineControllerTest.java#Compare` (Baseline comparison endpoint test)
- IMPLEMENTS → GITHUB_ISSUE `#231` (GC-N004: Structured Baseline Diff)
