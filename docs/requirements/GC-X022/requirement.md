---
id: GC-X022
title: "Scheduled processing retries failed real-time ingests"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-04-12T19:15:26.465172Z
updated_at: 2026-04-12T19:15:26.465172Z
---

# GC-X022 — Scheduled processing retries failed real-time ingests

## Statement

Scheduled processing shall retry observations that were captured by the real-time capture primitive but whose ingest failed, so that transient failures in real-time ingest do not result in permanently lost knowledge.

## Rationale

Real-time ingest is best-effort — it can fail due to transient LLM errors, file contention, or process crashes. Relying on real-time ingest alone risks silently losing captured observations. A retry pass in the scheduled processor is the safety net that guarantees every captured observation eventually becomes part of the knowledge base.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#526` (Knowledge system 5/6: scheduled processing and cold-path extraction)
