---
id: GC-GRC-024
title: "Architecture, Drift, and Coverage Workspaces"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:27:49.148800Z
updated_at: 2026-07-12T16:35:41.563712Z
---

# GC-GRC-024 — Architecture, Drift, and Coverage Workspaces

## Statement

Read-only workspaces shall expose the program's state for human review, consistent with the existing workspace pattern (GC-Q009/GC-Q010).

(a) Architecture-model view: components, boundaries, flows, and stores with data classifications and provenance, per snapshot version.

(b) Drift view: the current error signal per boundary and entity class, flagged entities, and trend over time.

(c) Coverage view: surface → threat → control → efficacy-test chains, gaps, and dispositions, answering 'is this boundary covered and how do we know'.

(d) Views shall be exposed via REST, MCP workspace tools, and the frontend.

## Rationale

Bootstrap review, disposition decisions, and drift triage are human gates; they need surfaces that show the model the way the threat-modeling and risk workspaces already do. Coverage chains are also the auditor-facing evidence of secure-by-design.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1137` (Issue #1137: GC-GRC-024 architecture, drift, and coverage workspaces)
