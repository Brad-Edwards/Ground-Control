---
id: GC-X013
title: "Knowledge base navigable without retrieval infrastructure"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:34.923654Z
updated_at: 2026-04-13T00:47:22.977371Z
---

# GC-X013 — Knowledge base navigable without retrieval infrastructure

## Statement

The knowledge base shall be navigable by an agent using only flat markdown files and a content index, without requiring vector embeddings, retrieval-augmented generation infrastructure, or any service beyond the file system and ordinary text search.

## Rationale

Flat markdown with a content index is adequate at moderate scale (hundreds of pages per repo) and avoids an entire category of operational burden: no embedding model to choose and maintain, no vector store to run, no re-indexing pipeline. It also makes the knowledge base usable by any agent with a Read tool and by humans browsing with Obsidian. The bet is that per-repo knowledge bases stay within the scale where flat navigation works.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js`
- IMPLEMENTS → DOCUMENTATION `docs/knowledge/SCHEMA.md`
- IMPLEMENTS → GITHUB_ISSUE `522`
- TESTS → TEST `mcp/ground-control/lib.mergereviewerarchitecturalreads-decision-record-.test.js` (Knowledge inbox tests: pages remain navigable as plain files without retrieval infrastructure)
