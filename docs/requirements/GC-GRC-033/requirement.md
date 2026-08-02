---
id: GC-GRC-033
title: "Incremental Derivation Performance and Caching"
status: DEPRECATED
type: NON_FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T23:26:52.022317Z
updated_at: 2026-07-12T16:35:41.563992Z
---

# GC-GRC-033 — Incremental Derivation Performance and Caching

## Statement

Derivation and assessment shall scale to real codebases without bottlenecking the implementation loop.

(a) Diff-scoped (in-loop) derivation shall be incremental: only the changed surface and its affected boundaries are re-derived, reusing cached facts for unchanged surfaces, keyed by content and analyzer/query-pack version.

(b) Full-scope (lane) derivation shall partition by boundary/package/asset and execute in parallel (per GC-GRC-016), with bounded, observable resource use suitable for large and monorepo codebases.

(c) Cache invalidation shall be correct: a change to code, boundaries, lattice, rule packs, or query-pack pins invalidates exactly the affected derived facts, never serving stale results past a provenance change.

(d) The in-loop GRC steps' added wall-clock cost shall be bounded and reported via telemetry (ADR-036), so the secure-by-design gates remain compatible with routine development cadence.

## Rationale

A security model recomputed from scratch on every change would make the loop unusable and invite bypass; an unusable gate is an unsafe gate. Incremental, correctly-invalidated, provenance-keyed caching is what makes continuous derivation viable on every run rather than a periodic batch.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1147` (Issue #1147: GC-GRC-033 incremental derivation performance and caching)
