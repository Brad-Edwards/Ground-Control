---
id: GC-X008
title: "Serialized writes within a knowledge base"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:05.647169Z
updated_at: 2026-04-12T19:14:05.647169Z
---

# GC-X008 — Serialized writes within a knowledge base

## Statement

Concurrent ingest against the same knowledge base shall be serialized so that no two ingest runs can produce an inconsistent state. Concurrent ingest against different knowledge bases shall remain independent and parallelizable.

## Rationale

Multiple observations can land in quick succession — two capture calls in a row, a capture call while a sweep is running, two overlapping sweeps. Without serialization per knowledge base, concurrent writes to the index or to the same page can silently lose updates. Serialization at the per-base level keeps the guarantee local and still allows different repos to process in parallel.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#523` (Knowledge system 2/6: capture primitive and real-time ingest engine)
