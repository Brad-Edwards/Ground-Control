---
id: GC-X001
title: "Per-repository agent-maintained knowledge base"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:13:21.046611Z
updated_at: 2026-04-12T19:13:21.046611Z
---

# GC-X001 — Per-repository agent-maintained knowledge base

## Statement

The system shall provide a per-repository knowledge base that is maintained collaboratively by coding agents and a scheduled processing service, such that organizational lessons observed during implementation work accumulate over time and are consulted by future implementation runs in the same repository.

## Rationale

Coding agents rediscover the same gotchas, conventions, and failure modes on every run because nothing accumulates between runs. Chat corrections, review comments, and fix-commit reasoning evaporate at session boundaries. A per-repository knowledge base compiled incrementally by the agents themselves closes that loop: the lesson learned in run N is available to run N+1, and the cost of maintenance approaches zero because the agents do the bookkeeping.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#778` (GC-X001: Per-repository agent-maintained knowledge base)
