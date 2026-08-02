---
id: GC-J008
title: "ADR Structured Content Model"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T19:45:44.183469Z
updated_at: 2026-03-14T19:45:44.183469Z
---

# GC-J008 — ADR Structured Content Model

## Statement

The system shall support a structured ADR content model with fields: title, status, context, decision, consequences (positive and negative), alternatives considered, and decision date. The system shall support both system-managed structured ADRs and links to external ADR documents (e.g., Markdown files in a repository). Structured content shall be queryable via REST API and MCP tools for agent consumption.

## Rationale

ADRs follow a well-established structure (Michael Nygard's format, adopted by MADR, adr-tools, and most engineering organizations). Structured content enables programmatic queries ("which ADRs considered technology X as an alternative?", "what are the negative consequences of accepted ADRs?") that flat-file ADRs cannot support. Agent-queryable structured content enables AI-assisted architecture decision analysis.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#286` (GC-J008: ADR Structured Content Model)
