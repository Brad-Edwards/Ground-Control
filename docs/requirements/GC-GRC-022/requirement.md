---
id: GC-GRC-022
title: "Quantitative Information-Flow Metrics"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 7
created_at: 2026-06-12T07:27:36.390125Z
updated_at: 2026-07-12T16:35:41.563640Z
---

# GC-GRC-022 — Quantitative Information-Flow Metrics

## Statement

Where tooling permits, identified sensitive flows shall carry quantitative leakage estimates feeding risk methodology inputs.

(a) Supported metrics shall include min-entropy / channel-capacity style leakage estimates for flows identified by GC-GRC-020, where an analyzer for the language/surface exists.

(b) Estimates shall feed methodology inputs (e.g., FAIR loss-event magnitude and frequency factors) on the linked risk assessments, with provenance and uncertainty metadata stored per the methodology-profile schema.

(c) Absence of a quantitative analyzer for a surface shall be recorded as a capture limit, with qualitative lattice findings (GC-GRC-006) remaining the floor.

## Rationale

Quantified information flow connects information-control theory directly to FAIR's quantitative axes, replacing gut-feel magnitude estimates for leak scenarios where measurement is possible. Tooling maturity varies by language, hence SHOULD with explicit declination rather than MUST.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1135` (Issue #1135: GC-GRC-022 quantitative information-flow metrics)
