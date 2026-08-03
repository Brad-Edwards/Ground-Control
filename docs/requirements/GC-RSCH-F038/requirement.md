---
id: GC-RSCH-F038
title: "FR-38 Build on existing Reactor workflow discipline and artifacts"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-26T00:07:06.579324Z
updated_at: 2026-05-26T01:48:32.984406Z
---

# GC-RSCH-F038 — FR-38 Build on existing Reactor workflow discipline and artifacts

## Statement

The system shall build on the existing Reactor repository assets where practical, preserving or adapting Reactor workflow discipline, methodology catalog semantics, citation MCP behavior, source-state rules, self-review checks, and artifact conventions when research becomes a Ground Control project type; replacing a Reactor asset requires explicit rationale.

## Rationale

The research project type should subsume and extend Reactor, not discard working discipline that already addresses observed failures: citation hallucination, domain leakage, invented procedure, imported framing without provenance, hollow output, and proceeding without source grounding. Ground Control should reuse or adapt what earned its place unless a specific implementation decision proves replacement is safer.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → ADR `architecture/adrs/055-research-workflow-skills-and-citation-mcp.md` (ADR-055 — research workflow skills and citation MCP (asset disposition table is the explicit rationale F038 requires))
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))
