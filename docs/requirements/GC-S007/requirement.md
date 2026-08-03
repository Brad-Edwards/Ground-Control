---
id: GC-S007
title: "Evidence Validation and Freshness Tracking"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T16:56:19.734270Z
updated_at: 2026-07-12T23:08:15.210191Z
---

# GC-S007 — Evidence Validation and Freshness Tracking

## Statement

The system shall validate collected evidence against expected schemas, track evidence freshness (last collected, next expected collection), and flag evidence that is stale (past its expected refresh interval), incomplete (missing expected fields), or invalid (schema violations). Stale evidence shall degrade the associated control's effectiveness rating.

## Rationale

Stale or invalid evidence provides false assurance. Freshness tracking ensures that compliance posture reflects current state, not historical snapshots. Degrading control effectiveness on stale evidence creates incentive to keep evidence current.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#216` (GC-S007: Evidence Validation and Freshness Tracking)
