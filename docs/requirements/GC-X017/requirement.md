---
id: GC-X017
title: "Administrative lifecycle control of the scheduler"
status: DRAFT
type: INTERFACE
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:57.990403Z
updated_at: 2026-04-12T19:14:57.990403Z
---

# GC-X017 — Administrative lifecycle control of the scheduler

## Statement

The system shall provide administrative commands to start, stop, restart, and query the status of the background scheduler that drives periodic knowledge base processing. Status queries shall report whether the scheduler is enabled, when it last ran, and when it is next scheduled to run.

## Rationale

Operators need to enable and disable scheduled processing without touching the underlying scheduler configuration directly. Status visibility is the difference between a system that silently stops working and one an operator can diagnose. A single entry point for lifecycle commands keeps operations simple and works the same way across host platforms.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#525` (Knowledge system 4/6: admin CLI and scheduler lifecycle)
