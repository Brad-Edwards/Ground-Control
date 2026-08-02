---
id: GC-W001
title: "Probabilistic Estimation Engine"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-11T19:00:19.876768Z
updated_at: 2026-04-11T19:00:19.876768Z
---

# GC-W001 — Probabilistic Estimation Engine

## Statement

The system shall support defining, composing, and evaluating probability distributions (normal, lognormal, beta, PERT, triangular, uniform, and custom) for modeling uncertain quantities in product and engineering decisions. Inputs shall be expressible as 90% confidence intervals or named distributions, with outputs including percentiles, means, and full distribution visualization. Distribution composition shall support arithmetic operations (addition, multiplication, division) and conditional logic so that complex cost, schedule, and value models can be assembled from component estimates.

## Rationale

Point estimates for cost, schedule, and value create false precision that leads to poor decisions. Probabilistic estimation captures what teams actually know — ranges of uncertainty — and propagates that uncertainty through models honestly. This is the foundational capability upon which Monte Carlo simulation, Cost of Delay, CBAM, and Value of Information analysis all depend.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#779` (GC-W001: Probabilistic Estimation Engine)
