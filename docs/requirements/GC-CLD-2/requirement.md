---
id: GC-CLD-2
title: "Architecture Registry and Lock-Level Boundary Model"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 9
created_at: 2026-07-04T02:45:31.440141Z
updated_at: 2026-07-28T03:05:19.179793Z
---

# GC-CLD-2 — Architecture Registry and Lock-Level Boundary Model

## Statement

Ground Control shall support an architecture registry for CLD-managed boundaries. The registry shall describe modules, allowed dependency edges, ownership, contract-bearing boundary identifiers, lock levels (locked, guarded, fluid), risk scores, and gate-selection metadata as data rather than prose. Registry changes that raise, lower, add, remove, or materially alter a locked or guarded boundary shall be treated as design-authority events with machine-decidable exit criteria.

## Rationale

CLD depends on knowing which boundaries are contracted, who owns them, and which change protocol applies. The registry is the data substrate that lets later policy checks and agents reason about protected paths, risk-scored oracle composition, and architecture drift without hard-coded file lists.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1295` (Architecture-as-code registry: module graph, allowed edges, and lock levels as data)
- DOCUMENTS → ADR `architecture/adrs/087-contract-locked-development-methodology.md` (ADR-087: Contract-Locked Development Methodology)
- DOCUMENTS → GITHUB_ISSUE `1291` (ADR: contract-locked development methodology)
- DOCUMENTS → ADR `ADR-087` (ADR-087: Contract-Locked Development Methodology)
