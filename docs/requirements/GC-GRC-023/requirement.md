---
id: GC-GRC-023
title: "GRC Configuration Surface"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:27:43.436161Z
updated_at: 2026-07-12T16:35:41.563674Z
---

# GC-GRC-023 — GRC Configuration Surface

## Statement

The repository's .ground-control.yaml shall carry a GRC configuration block, read through the repo-context contract (ADR-027).

(a) Configurable: boundary declarations (GC-GRC-004), lattice taxonomy and policy (GC-GRC-006), adapter enablement and query/rule-pack pins, assessment schedules and event triggers (GC-GRC-017), lane review-gate policy, and repo-mirroring policy (GC-GRC-026).

(b) Configuration shall validate with actionable errors; invalid configuration fails closed.

(c) Absent configuration shall yield safe defaults: derivation-backed screening on, blocking gates on, no repo mirroring, default lattice, with derivation-coverage declinations recorded explicitly.

(d) Only structural, non-sensitive metadata belongs in the repo block; model content stays server-side.

## Rationale

One declared place per repo for GRC policy keeps the system agent-neutral (ADR-027) and reviewable in PRs, while the safe-default posture means an unconfigured repo gets protection rather than silence.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1136` (Issue #1136: GC-GRC-023 GRC configuration surface (.ground-control.yaml grc block))
