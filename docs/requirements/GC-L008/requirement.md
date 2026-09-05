---
id: GC-L008
title: "Asset and Observation MCP Operations"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:41.596577Z
updated_at: 2026-05-21T04:13:05.638716Z
---

# GC-L008 — Asset and Observation MCP Operations

## Statement

The system shall expose MCP operations for graph-native asset, topology, observation, and evidence entities, including CRUD, linking, graph traversal, and state-aware query workflows, with parity to the REST API.

## Rationale

If agents are expected to manage risk, controls, and assurance in a graph-native factory, they need direct MCP access to the asset and state substrate those workflows depend on, not only to risk and control records layered above it.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js`
- IMPLEMENTS → PULL_REQUEST `#939` (fixed: bring gc_asset and gc_observation MCP tools to REST parity (GC-L008))
- IMPLEMENTS → GITHUB_ISSUE `#730` (GC-L008: Asset and Observation MCP Operations)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-asset.js`
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-observation.js`
- TESTS → TEST `mcp/ground-control/gc-asset.test.js`
- TESTS → TEST `mcp/ground-control/gc-observation.test.js`
