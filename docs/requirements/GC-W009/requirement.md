---
id: GC-W009
title: "Delivery Forecasting from Historical Data"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-11T19:01:05.527748Z
updated_at: 2026-04-11T19:01:05.527748Z
---

# GC-W009 — Delivery Forecasting from Historical Data

## Statement

The system shall generate probabilistic delivery forecasts grounded in actual project throughput data including cycle time, throughput, and work-in-progress. Forecasts shall answer 'when will N items be done' and 'how many items can we complete by date X' with confidence intervals using Monte Carlo simulation over historical distributions. The system shall support forecast backtesting against historical data to validate forecast accuracy and shall detect throughput shifts (via control charts or similar) that invalidate historical baselines.

## Rationale

Schedule forecasting based on gut feel or ideal-day estimation is consistently unreliable. Monte Carlo forecasting from actual throughput data produces calibrated probabilistic forecasts ('85% confidence of completing by March 15') that decision-makers can use for realistic planning. Backtesting ensures the forecasting model remains honest, and shift detection prevents stale data from corrupting forecasts after team or process changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#787` (GC-W009: Delivery Forecasting from Historical Data)
