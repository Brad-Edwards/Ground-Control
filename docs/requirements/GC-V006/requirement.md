---
id: GC-V006
title: "Exception-to-Finding Escalation"
status: DEPRECATED
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-14T19:35:13.349836Z
updated_at: 2026-07-12T23:08:15.210686Z
---

# GC-V006 — Exception-to-Finding Escalation

## Statement

The system shall support automated escalation pathways: expired control exceptions (GC-I014) without renewal shall automatically create findings (GC-V001); policy violations detected by GC-K003 shall automatically create findings. Escalation shall preserve source linkage and set appropriate severity.

## Rationale

Manual escalation is unreliable. Automated escalation ensures expired exceptions and policy violations are never silently ignored. This turns compliance monitoring from passive reporting into active enforcement.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#284` (GC-V006: Exception-to-Finding Escalation)
