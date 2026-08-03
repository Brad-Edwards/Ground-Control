---
id: GC-GRC-013
title: "Knock-On Propagation and Reassessment Triggers"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:26:13.098448Z
updated_at: 2026-07-12T16:35:41.562832Z
---

# GC-GRC-013 — Knock-On Propagation and Reassessment Triggers

## Statement

When changed code is linked — directly or via graph traversal — to existing GRC entities, the change shall propagate.

(a) Reassessment triggers on affected risk scenarios and treatment plans shall fire.

(b) Multi-hop traversal (code → threat/control → risk → asset → dependent entities) shall flag affected entities for review, using the asset-topology and impact-analysis graph substrate.

(c) Flags shall surface in the active run's stale set (so the run sees its own knock-on effects) and in the GRC workspaces.

(d) Flags shall persist until addressed or dispositioned; they shall not expire silently.

## Rationale

A change's security impact rarely stops at the file it touches. Propagation through the graph is what keeps the recorded risk picture true as the codebase moves — the maintain-always half of secure-by-design — and builds on the planned impact-graph substrate (GC-H006, GC-M013, GC-M017).

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1126` (Issue #1126: GC-GRC-013 knock-on propagation and reassessment triggers)
