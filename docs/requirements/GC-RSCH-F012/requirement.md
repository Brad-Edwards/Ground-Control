---
id: GC-RSCH-F012
title: "FR-12 Resolve every candidate source through deterministic bibliographic services such as DOI, arXiv, PMID, Crossref, OpenA..."
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 13
created_at: 2026-05-25T22:47:46.379117Z
updated_at: 2026-05-26T01:48:16.557967Z
---

# GC-RSCH-F012 — FR-12 Resolve every candidate source through deterministic bibliographic services such as DOI, arXiv, PMID, Crossref, OpenA...

## Statement

Resolve every candidate source through deterministic bibliographic services such as DOI, arXiv, PMID, Crossref, OpenAlex, or Zotero.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1010` (Research source records and deterministic bibliographic resolution)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `mcp/citation/citation_mcp/resolve.py` (citation MCP — deterministic DOI/arXiv/PMID/ISBN resolver via Crossref/OpenAlex)
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))
