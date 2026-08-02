---
id: GC-X004
title: "Knowledge base structure: schema, index, log, content pages"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:13:38.498238Z
updated_at: 2026-04-13T00:47:20.134041Z
---

# GC-X004 — Knowledge base structure: schema, index, log, content pages

## Statement

A knowledge base shall consist of a schema file describing its conventions, a content index listing all pages with summaries, a chronological append-only log of changes, and content pages organized per the schema. Content pages shall be plain markdown files the agent can read and write without specialized retrieval infrastructure.

## Rationale

The schema encodes the conventions that let an agent maintain the knowledge base without human supervision. The content index is how the agent decides between updating an existing page and creating a new one, and how consuming agents discover relevant pages during exploration. The log is the human-readable timeline of the wiki's evolution and the machine-readable record of what the agent has processed. Plain markdown makes the knowledge base usable by any agent and by humans via Obsidian.

## Traceability

- IMPLEMENTS → DOCUMENTATION `docs/knowledge/SCHEMA.md`
- IMPLEMENTS → DOCUMENTATION `docs/knowledge/index.md`
- IMPLEMENTS → DOCUMENTATION `docs/knowledge/log.md`
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js`
- IMPLEMENTS → GITHUB_ISSUE `522`
- TESTS → TEST `mcp/ground-control/lib.mergereviewerarchitecturalreads-decision-record-.test.js` (Knowledge inbox structure tests (schema, index, log, content pages))
