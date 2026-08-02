---
id: GC-W005
title: "Calibrated Estimation Tracking"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-04-11T19:00:41.952081Z
updated_at: 2026-04-11T19:00:41.952081Z
---

# GC-W005 — Calibrated Estimation Tracking

## Statement

The system shall track estimation accuracy over time for individual estimators, computing calibration metrics including Brier scores, calibration curves, and confidence interval hit rates (percentage of actuals falling within stated 90% intervals). The system shall provide feedback to estimators on systematic overconfidence or underconfidence and shall track calibration improvement over successive estimation rounds. Historical calibration data shall be usable to adjust future estimates — widening intervals for consistently overconfident estimators and narrowing for underconfident ones.

## Rationale

Calibrated estimation is the single highest-leverage technique from Hubbard's Applied Information Economics. Research shows that untrained estimators' 90% confidence intervals contain the true value only 50-60% of the time. With tracking and feedback, accuracy improves dramatically within a few rounds. Every other quantitative decision framework depends on input quality, and calibration is how input quality is measured and improved.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#783` (GC-W005: Calibrated Estimation Tracking)
