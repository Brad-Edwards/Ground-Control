---
id: GC-S006
title: "Event-Driven Evidence Collection"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T16:56:15.149193Z
updated_at: 2026-07-12T23:08:15.210126Z
---

# GC-S006 — Event-Driven Evidence Collection

## Statement

The system shall support event-driven evidence collection triggered by webhooks, system events, or threshold violations (e.g., a new user provisioned, a security group changed, a compliance scan failed), collecting evidence immediately when compliance-relevant events occur.

## Rationale

Scheduled collection misses inter-cycle events. Event-driven collection provides real-time evidence for controls that require continuous monitoring, enabling agents to react to compliance-relevant changes as they happen.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#215` (GC-S006: Event-Driven Evidence Collection)
