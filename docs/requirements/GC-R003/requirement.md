---
id: GC-R003
title: "Third-Party Lifecycle Management"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T16:55:19.753502Z
updated_at: 2026-03-14T16:55:19.753502Z
---

# GC-R003 — Third-Party Lifecycle Management

## Statement

The system shall enforce a third-party lifecycle state machine: prospect -> onboarding -> active -> under-review -> active (re-approved), active -> offboarding -> terminated. Under-review -> offboarding (failed review). No transitions out of terminated without creating a new entity.

## Rationale

Vendor relationships have a lifecycle with compliance implications at each stage. Onboarding requires due diligence, offboarding requires data return/destruction. A state machine ensures compliance gates are not skipped.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#202` (GC-R003: Third-Party Lifecycle Management)
