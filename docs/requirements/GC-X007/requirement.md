---
id: GC-X007
title: "Ingest consistency: update existing pages, do not duplicate"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:00.182735Z
updated_at: 2026-04-12T19:14:00.182735Z
---

# GC-X007 — Ingest consistency: update existing pages, do not duplicate

## Statement

Knowledge base ingest shall read the current state of the knowledge base before writing, so that an observation matching an existing page updates that page rather than creating a duplicate. Ingest shall preserve existing cross-references and page identities whenever the observation refines or extends content already present.

## Rationale

Without consulting existing content, ingest produces near-duplicate pages, splits the evidence for a single topic across multiple entries, and destroys the cross-referenced structure that makes the wiki useful. Consistency is the property that keeps the wiki compounding rather than fragmenting over time.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#523` (Knowledge system 2/6: capture primitive and real-time ingest engine)
