---
id: GC-RSCH-N010
title: "NFR-10 Extensibility"
status: DRAFT
type: NON_FUNCTIONAL
priority: SHOULD
wave: 14
created_at: 2026-05-25T22:47:46.945041Z
updated_at: 2026-05-25T22:47:46.945041Z
---

# GC-RSCH-N010 — NFR-10 Extensibility

## Statement

Extensibility: methods, search providers, reviewers, extraction schemas, writing templates, and output formats should be plugin-like.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1004` (Research workflow evaluation, observability, and adapters)
- DOCUMENTS → GITHUB_ISSUE `1021` (Research full-text evidence Q&A adapter)
- DOCUMENTS → GITHUB_ISSUE `1029` (Research adapter/plugin boundary)
- DOCUMENTS → GITHUB_ISSUE `1030` (Research local/offline execution mode)
- DOCUMENTS → ADR `architecture/adrs/072-research-rest-and-mcp-tool-surface.md` (ADR-072 — Research REST and MCP Tool Surface (extension seam stays within the curated tool surface; plugin families remain DRAFT))
- DOCUMENTS → ADR `architecture/adrs/073-research-extensibility-and-adapter-boundary.md` (ADR-073 — Research Extensibility and Adapter Boundary (defines the extensionId/version/capability seam for N010; plugin families remain DRAFT))
