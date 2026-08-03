---
id: GC-GRC-031
title: "Agent-Facing GRC Context Retrieval by Code Artifact and Boundary"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T23:26:35.809966Z
updated_at: 2026-07-12T16:35:41.563947Z
---

# GC-GRC-031 — Agent-Facing GRC Context Retrieval by Code Artifact and Boundary

## Statement

Any agent reasoning over a project's Ground Control graph shall be able to retrieve the GRC context for a code artifact or boundary through graph links, the same way risks and threat models are already reachable.

(a) A reverse-lookup surface (MCP tool + REST + graph traversal) shall, given a code path, boundary, or change surface, return the linked architecture-model elements, data classifications, threats, controls (with implementation/efficacy status), risk scenarios and assessments, attack paths, open gaps, and recorded dispositions.

(b) The /implement workflow shall use this surface so that screening, planning, and implementation operate against the actual graph context for the touched code — not a re-derivation from scratch each run.

(c) All GRC artifacts (architecture model, DFDs, threats, risks, controls, assessments) shall be graph-native nodes with typed edges to code, requirements, and each other, so traversal reaches them without any repo-side files.

(d) The surface shall be the canonical read path for GRC context; agents shall not depend on parsing repo files to obtain it.

## Rationale

The whole graph is only useful if an agent touching a piece of code can reach the threats, controls, and risks that bear on it. Making GRC context a first-class, code-keyed reverse lookup — mirroring existing reverse traceability — is what lets continuous secure-by-design reasoning happen without re-deriving everything, and keeps the graph (not the repo) as the source of truth.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1145` (Issue #1145: GC-GRC-031 agent-facing GRC context retrieval by code artifact and boundary)
