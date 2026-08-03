---
id: GC-GRC-009
title: "Derivation-Backed Change Screening"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:25:38.969354Z
updated_at: 2026-07-11T23:43:44.555564Z
---

# GC-GRC-009 — Derivation-Backed Change Screening

## Statement

The /implement screening gate shall compute security relevance from the change itself via derivation, not from agent assertion.

(a) Screening shall run the diff through the derivation adapters and classify the touched surface, producing three sets: the impact set (existing threats/risks/controls/model elements the change touches), the gap set (security-relevant touched surfaces with no model coverage), and the stale set (linked GRC entities whose underlying code changed).

(b) An empty or absent baseline shall yield a gap set covering the entire touched security-relevant surface. There shall be no passing 'no baseline' verdict: a missing baseline is work the run must do (scoped to the touched boundaries), not a recorded declination.

(c) Candidate threats and controls from the enumeration rules (GC-GRC-007/008) shall attach to the screening record.

(d) The screening record shall remain durable and schema-versioned on the issue thread, extending the GC-O012 record contract; the computed classification shall be reproducible from the recorded provenance.

## Rationale

Today the verdict is the agent's self-certified judgment and an empty baseline short-circuits to a free pass — the exact failure observed when a major deployment-pipeline change received no security work. Computing the classification from derived facts removes both failure modes structurally, per the ADR-057 lesson that enforcement must live in the tool layer.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1122` (Issue #1122: GC-GRC-009 derivation-backed change screening (kill the no_baseline pass))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (v2 screening compute + reconciliation recompute (classifyGrcScreening, runComputeGrcScreening, reconcileGrcScreeningV2))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_post_grc_screening v2 tool registration (computed contract, no paths override))
- IMPLEMENTS → DOCUMENTATION `skills/implement/steps/step-03.5-grc-screening.md` (Step 3.5 derivation-backed screening prose (computed contract))
- IMPLEMENTS → ADR `architecture/adrs/057-per-run-grc-screening-gate.md` (ADR-057 v2 amendment: derivation-backed screening contract)
- TESTS → TEST `mcp/ground-control/gc-grc-screening-v2.test.js` (v2 classification, empty-baseline gap, stale, candidates, provenance, runner tests)
- TESTS → TEST `mcp/ground-control/gc-grc-reconciled.test.js` (v2 reconciliation recompute-from-final-diff freshness + gap-blocks tests)
