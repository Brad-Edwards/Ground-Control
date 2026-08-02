---
id: GC-GRC-017
title: "Scheduled and Event-Triggered Reassessment"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:26:46.762973Z
updated_at: 2026-07-12T16:35:41.562915Z
---

# GC-GRC-017 — Scheduled and Event-Triggered Reassessment

## Statement

The assessment lane shall be schedulable and event-triggered, not only manually invoked.

(a) Cron-style schedules per project (for example, quarterly full reassessment) shall be supported.

(b) Event triggers shall include, at minimum: control-pack version changes, methodology-profile changes, rule-pack/query-pack pin changes, KRI breach signals, arrival of a risk assessment's reassessmentRequiredAt, and drift exceeding policy thresholds (GC-GRC-019).

(c) Triggered runs shall record durable results and raise flags through the same channels as manual runs.

(d) Trigger configuration shall live in the project GRC configuration surface (GC-GRC-023).

## Rationale

Maintain-always means reassessment cannot depend on someone remembering to ask. The domain model already carries the hooks (reassessmentTriggers, reassessmentRequiredAt, KRIs); this wires them to an executor.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1130` (Issue #1130: GC-GRC-017 scheduled and event-triggered reassessment)
