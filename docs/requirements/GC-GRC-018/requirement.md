---
id: GC-GRC-018
title: "Baseline Bootstrap for Existing Codebases"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:27:03.132865Z
updated_at: 2026-07-12T16:35:41.563494Z
---

# GC-GRC-018 — Baseline Bootstrap for Existing Codebases

## Statement

The assessment lane in model mode over a full project shall bootstrap a GRC baseline for an existing codebase.

(a) Bootstrap shall derive the whole-repo architecture model, enumerate candidate threats per boundary, identify candidate controls, and propose risk scenarios — partitioned and parallel per GC-GRC-016(c).

(b) Proposed entities shall pass human/agent review before committing to the graph; review granularity (per boundary, per entity class) shall be configurable.

(c) On completion, the project's modeled-surface coverage shall be recorded, so subsequent in-loop runs face gap sets only for genuinely new or previously-declined surfaces.

(d) Bootstrap shall be re-runnable and idempotent: re-running reconciles against the existing baseline rather than duplicating it.

## Rationale

Every consumer repo starts unmodeled; without a bootstrap path the in-loop gates either block everything or pass everything. One reviewed full-scope run converts the cold-start problem into ordinary incremental maintenance for the life of the codebase.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1131` (Issue #1131: GC-GRC-018 baseline bootstrap for existing codebases)
