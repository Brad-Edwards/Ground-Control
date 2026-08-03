---
id: GC-X006
title: "Agent capture primitive for knowledge observations"
status: DRAFT
type: INTERFACE
priority: MUST
wave: 6
created_at: 2026-04-12T19:13:49.205653Z
updated_at: 2026-04-12T19:13:49.205653Z
---

# GC-X006 — Agent capture primitive for knowledge observations

## Statement

The system shall provide a capture primitive that allows a coding agent to record an observation in the knowledge base inbox of a given repository, with a structured source citation and optional tags, and receive confirmation that the observation was stored.

## Rationale

Capture has to be cheap enough that agents use it in the moment of surprise, not at the end of a run when the context is cold. A structured capture primitive with a fixed input shape keeps the write deterministic and lets the downstream processing trust the metadata. Returning the stored location lets the agent reference the entry or retry if it needs to.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#523` (Knowledge system 2/6: capture primitive and real-time ingest engine)
