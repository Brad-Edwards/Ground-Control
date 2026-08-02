---
id: GC-W002
title: "Monte Carlo Decision Simulation"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-11T19:00:24.909265Z
updated_at: 2026-04-11T19:00:24.909265Z
---

# GC-W002 — Monte Carlo Decision Simulation

## Statement

The system shall run Monte Carlo simulations over composed probabilistic models with configurable sample counts (default 10,000). Results shall include percentile summaries (P10, P50, P90), probability of exceeding thresholds, and scenario comparison across alternative strategies. The system shall provide sensitivity analysis identifying which input variables most affect outcomes, presented as tornado diagrams and contribution-to-variance metrics, so that decision-makers know where reducing uncertainty would most improve decision quality.

## Rationale

Monte Carlo simulation is the standard technique for propagating uncertainty through multi-variable decision models. Without it, teams either ignore uncertainty or resort to worst-case/best-case scenarios that distort decisions. Sensitivity analysis is essential for directing investigation effort — it answers 'what should we learn more about before committing' rather than investigating everything equally.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#780` (GC-W002: Monte Carlo Decision Simulation)
