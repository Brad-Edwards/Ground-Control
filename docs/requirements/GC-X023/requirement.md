---
id: GC-X023
title: "Idempotent scheduled processing"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:15:35.360114Z
updated_at: 2026-04-12T19:15:35.360114Z
---

# GC-X023 — Idempotent scheduled processing

## Statement

Scheduled processing shall be idempotent with respect to its persisted state: re-running a scheduled processing invocation against the same repository state and the same watermark shall not produce duplicate pages, duplicate log entries, or duplicate commits.

## Rationale

Scheduled processing runs on timers that may be re-triggered by restarts, manual retries, or recovery from failed runs. An idempotent processor is the only safe way to recover from partial failures without corrupting the knowledge base. Idempotency also lets operators re-run a sweep to verify behavior without worrying about side effects.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#526` (Knowledge system 5/6: scheduled processing and cold-path extraction)
