---
id: GC-S008
title: "Evidence Collection Audit Trail"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T16:56:23.753459Z
updated_at: 2026-07-12T23:08:15.210212Z
---

# GC-S008 — Evidence Collection Audit Trail

## Statement

The system shall maintain a complete audit trail of all evidence collection activities, recording: which adapter collected what evidence, from which source system, at what time, triggered by what (schedule or event), with what result (success, partial, failure), and any errors encountered.

## Rationale

Auditors need to verify that evidence was collected correctly and completely. The collection audit trail answers questions like "when was this evidence last refreshed?" and "why is this evidence missing?" — essential for audit readiness.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#217` (GC-S008: Evidence Collection Audit Trail)
