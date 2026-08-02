---
id: GC-V005
title: "Finding Aggregation & Trending"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T19:35:05.299977Z
updated_at: 2026-07-12T23:08:15.210668Z
---

# GC-V005 — Finding Aggregation & Trending

## Statement

The system shall support aggregate finding reporting including findings by severity, type, source, age, control, risk, and affected asset or asset class; trend analysis showing finding open and close rates over time; and overdue remediation tracking with aging buckets. Reports shall be queryable via REST API and MCP tools.

## Rationale

Individual findings are tactical; aggregate trends are strategic. In a graph-native assurance model, trending by affected operational asset context is as important as trending by control or risk.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#283` (GC-V005: Finding Aggregation & Trending)
