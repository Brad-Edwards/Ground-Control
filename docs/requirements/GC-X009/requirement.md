---
id: GC-X009
title: "Failed ingest retains source material for retry"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:10.408416Z
updated_at: 2026-04-12T19:14:10.408416Z
---

# GC-X009 — Failed ingest retains source material for retry

## Statement

When ingest fails to process a source observation, the source material shall remain in the inbox so that a later processing run can retry it. Ingest shall not delete or move a source until it has been successfully integrated into the knowledge base.

## Rationale

Ingest involves a subprocess call to an LLM and a sequence of file writes, any of which can fail. If the source is deleted or moved before the integration completes, the observation is lost and there is no way to recover it. Keeping the source in place until success gives the scheduled sweep a retry surface and makes the whole pipeline crash-safe.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#523` (Knowledge system 2/6: capture primitive and real-time ingest engine)
