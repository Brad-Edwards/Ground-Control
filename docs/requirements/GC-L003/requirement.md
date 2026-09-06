---
id: GC-L003
title: "MCP Traceability Tools"
status: ACTIVE
type: INTERFACE
priority: MUST
wave: 1
created_at: 2026-03-13T23:14:47.679671Z
updated_at: 2026-05-16T04:54:48.473165Z
---

# GC-L003 — MCP Traceability Tools

## Statement

The system shall expose traceability operations (create links, query links, navigate the artifact graph) as MCP tools, enabling agents to build and query the traceability graph.

## Rationale

Traceability link management is core agent workflow. An agent that creates code implementing a requirement should be able to create the traceability link in the same workflow.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP Server - Traceability tools)
- DOCUMENTS → GITHUB_ISSUE `#668` (GC-L003: MCP Traceability Tools)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- TESTS → TEST `mcp/ground-control/traceability-tools.test.js` (MCP traceability tool tests (get/get-by-artifact/create/delete/relation/graph))
- TESTS → TEST `mcp/ground-control/link-create.test.js` (Shared link-create contract tests (gc_create_traceability_link surface))
