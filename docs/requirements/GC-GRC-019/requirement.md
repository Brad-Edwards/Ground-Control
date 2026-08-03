---
id: GC-GRC-019
title: "GRC Drift Control Loop"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:27:09.781746Z
updated_at: 2026-07-12T16:35:41.563535Z
---

# GC-GRC-019 — GRC Drift Control Loop

## Statement

GRC state currency shall be maintained as a closed control loop with a computed error signal.

(a) Drift shall be defined as the divergence between freshly re-derived facts (from the current codebase) and the recorded architecture model and GRC graph.

(b) A drift analysis (gc_analyze kind or equivalent) shall compute the error signal on demand and on schedule, per boundary and per entity class.

(c) Staleness of threat models, risk assessments, and control links shall be computed from drift — never inferred by an LLM re-reading code.

(d) Drift exceeding policy thresholds shall raise flags (workspace indicators and tracked items) and may trigger lane reassessment per GC-GRC-017.

(e) Drift metrics shall be exposed for dashboards and telemetry; the loop's objective is to drive the error signal to zero.

## Rationale

Control theory framing: the codebase is the plant, the GRC graph is the model, changes are disturbances, and drift is the measurable error. This makes 'keep GRC current and flag issues' a closed-loop property with a number attached instead of a vibe.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1132` (Issue #1132: GC-GRC-019 GRC drift control loop)
