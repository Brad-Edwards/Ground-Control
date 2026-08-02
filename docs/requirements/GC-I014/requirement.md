---
id: GC-I014
title: "Control Exception & Waiver Tracking"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T19:33:21.467569Z
updated_at: 2026-07-12T23:08:15.210514Z
---

# GC-I014 — Control Exception & Waiver Tracking

## Statement

The system shall support time-bound exceptions to control requirements with: justification, compensating controls, approver, expiration date, and renewal workflow. Expired exceptions without renewal shall be flaggable as findings (GC-V001). Active exceptions shall be visible in compliance posture reporting.

## Rationale

Not every control can be fully implemented immediately. Exceptions with compensating controls are a standard compliance mechanism. Tracking them prevents "permanent temporary exceptions" — a common compliance failure mode. Expiration-based escalation ensures exceptions are periodically re-justified.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#272` (GC-I014: Control Exception & Waiver Tracking)
