---
id: GC-X020
title: "Incremental processing via per-repository watermark"
status: DRAFT
type: NON_FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-04-12T19:15:17.280682Z
updated_at: 2026-04-12T19:15:17.280682Z
---

# GC-X020 — Incremental processing via per-repository watermark

## Statement

Scheduled processing shall be incremental: each run shall only consider source material that has appeared since the previous successful run for the same repository. The system shall persist enough state per repository to determine, on any subsequent run, which source material has already been processed.

## Rationale

Reprocessing everything on every run is expensive and redundant. An incremental model with per-repo state is the normal pattern for durable batch processors and is the only way to keep run time bounded as the history grows. Persisting the state separately from the registry means deregister-then-register cycles do not lose incremental progress.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#526` (Knowledge system 5/6: scheduled processing and cold-path extraction)
