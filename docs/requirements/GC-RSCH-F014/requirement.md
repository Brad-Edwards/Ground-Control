---
id: GC-RSCH-F014
title: "FR-14 Support backward snowballing from actual reference arrays and forward snowballing from OpenAlex/citation indexes"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 13
created_at: 2026-05-25T22:47:46.407375Z
updated_at: 2026-05-26T01:48:18.044956Z
---

# GC-RSCH-F014 — FR-14 Support backward snowballing from actual reference arrays and forward snowballing from OpenAlex/citation indexes

## Statement

Support backward snowballing from actual reference arrays and forward snowballing from OpenAlex/citation indexes.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1012` (Research forward and backward snowballing)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `mcp/citation/citation_mcp/search.py` (citation MCP — forward snowballing via OpenAlex (search_forward); backward via cite_resolve reference arrays)
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))
