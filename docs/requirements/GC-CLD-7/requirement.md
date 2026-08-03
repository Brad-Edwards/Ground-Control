---
id: GC-CLD-7
title: "CLD Evaluation Harness and Process Metrics"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 9
created_at: 2026-07-04T02:46:19.597561Z
updated_at: 2026-07-04T02:46:19.597561Z
---

# GC-CLD-7 — CLD Evaluation Harness and Process Metrics

## Statement

Ground Control shall measure CLD effectiveness with an evaluation harness that records per-boundary first-pass green rate, iterations to green, gate cost, mutation score, invariant coverage, seeded-defect catch rate, defect escape rate, and enough run metadata to compare CLD against the ordinary implementation lane. The harness shall produce an explicit go, adjust, or stop recommendation after the pilot.

## Rationale

CLD's promise is reducing error and rework, not adding ceremony. The method needs process data so gate placement, reference-model budget, and mutation thresholds become measured operations-research questions instead of taste.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1297` (Evaluation harness: correctness metrics and seeded-defect oracle scoring)
- DOCUMENTS → ADR `architecture/adrs/087-contract-locked-development-methodology.md` (ADR-087: Contract-Locked Development Methodology)
- DOCUMENTS → GITHUB_ISSUE `1291` (ADR: contract-locked development methodology)
- DOCUMENTS → ADR `ADR-087` (ADR-087: Contract-Locked Development Methodology)
