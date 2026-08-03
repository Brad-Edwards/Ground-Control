---
id: GC-W007
title: "Multi-Criteria Decision Analysis"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-04-11T19:00:53.284621Z
updated_at: 2026-04-11T19:00:53.284621Z
---

# GC-W007 — Multi-Criteria Decision Analysis

## Statement

The system shall support structured multi-criteria decision analysis (MCDA) for ranking alternatives against weighted criteria, supporting at minimum AHP (Analytic Hierarchy Process), TOPSIS, and PROMETHEE II methods. Criteria weights shall be elicitable through pairwise comparison (with consistency ratio checking) or direct assignment. The system shall present ranking results with robustness analysis showing how sensitive the ranking is to changes in weights, so decision-makers can see whether the top-ranked alternative is clearly dominant or statistically tied with others.

## Rationale

Engineering decisions often involve multiple incommensurable criteria — latency, cost, maintainability, time-to-market, team skill availability — that cannot be reduced to a single monetary value. MCDA methods provide mathematically rigorous ranking across multiple dimensions. Offering multiple methods (AHP, TOPSIS, PROMETHEE) allows validation — if the same alternative wins under different methods, confidence in the decision increases.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#785` (GC-W007: Multi-Criteria Decision Analysis)
