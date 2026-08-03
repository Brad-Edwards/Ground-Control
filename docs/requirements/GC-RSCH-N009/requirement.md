---
id: GC-RSCH-N009
title: "NFR-9 Interoperability"
status: DRAFT
type: NON_FUNCTIONAL
priority: SHOULD
wave: 14
created_at: 2026-05-25T22:47:46.931679Z
updated_at: 2026-05-25T22:47:46.931679Z
---

# GC-RSCH-N009 — NFR-9 Interoperability

## Statement

Interoperability: the system should integrate with Zotero, Crossref, OpenAlex, Unpaywall, arXiv, PubMed, DOI, BibTeX, RIS, CSL-JSON, Git, and local markdown.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1003` (Research graph projection and traversal support)
- DOCUMENTS → GITHUB_ISSUE `1004` (Research REST and MCP tool surface)
- DOCUMENTS → GITHUB_ISSUE `1010` (Research source records and deterministic bibliographic resolution)
- DOCUMENTS → GITHUB_ISSUE `1011` (Research Zotero and source-store integration)
- DOCUMENTS → GITHUB_ISSUE `1025` (Research export formats)
- DOCUMENTS → GITHUB_ISSUE `1030` (Research local/offline execution mode)
- DOCUMENTS → ADR `architecture/adrs/071-research-interoperability-source-identity.md` (ADR-071 — interoperability/source-identity boundary (external integrations not yet built; requirement stays DRAFT))
- DOCUMENTS → ADR `architecture/adrs/072-research-rest-and-mcp-tool-surface.md` (ADR-072 — Research REST and MCP Tool Surface (interoperability surface boundary; integrations remain DRAFT))
- DOCUMENTS → ADR `architecture/adrs/073-research-extensibility-and-adapter-boundary.md` (ADR-073 — Research Extensibility and Adapter Boundary (provider/format interoperability via the adapter seam; integrations remain DRAFT))
