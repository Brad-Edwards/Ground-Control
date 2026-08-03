---
id: GC-X015
title: "Knowledge system never blocks implementation completion"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:48.634921Z
updated_at: 2026-04-12T19:14:48.634921Z
---

# GC-X015 — Knowledge system never blocks implementation completion

## Statement

The availability, health, or outcome of the knowledge system shall not block the completion of an /implement run. Failures in capture, ingest, sweep, or lint shall degrade the knowledge system silently and permit the implementation workflow to finish normally.

## Rationale

Capture is exhaust, not a gate. The knowledge system is supplementary to the core implementation workflow and must not become a single point of failure for shipping code. Treating knowledge failures as non-blocking keeps the /implement workflow resilient and encourages agents to use capture liberally without worrying that a transient ingest failure will halt their work.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#524` (Knowledge system 3/6: consumption and /implement integration)
