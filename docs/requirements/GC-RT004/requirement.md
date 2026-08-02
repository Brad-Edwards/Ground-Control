---
id: GC-RT004
title: "Ingest runtime events into an honest live-status projection"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 8
created_at: 2026-07-30T04:12:11.661076Z
updated_at: 2026-07-30T04:12:11.661076Z
---

# GC-RT004 — Ingest runtime events into an honest live-status projection

## Statement

Ground Control shall ingest ordered acknowledged runtime events and derive a timestamped live-status projection that distinguishes reported, stale, disconnected, stalled, and terminal state without inventing executor state.

## Rationale

This requirement is part of the private Agent Deck runtime program and is independently owned by the Ground Control control-plane repository.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#1487` (GC-RT004: Ingest runtime events into an honest live-status projection)
