---
id: GC-T008
title: "Risk Aggregation and Reporting"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T19:34:05.352509Z
updated_at: 2026-07-12T23:08:15.210424Z
---

# GC-T008 — Risk Aggregation and Reporting

## Statement

The system shall support aggregate risk reporting using methodology-appropriate outputs, including qualitative heat maps when supported by the selected methodology, quantitative distributions, percentile and exceedance views, trends over time, risk distribution by category, status, asset class, owner, or criticality, top-N scenarios or records by current assessment output, and executive risk posture summaries. Reports shall be queryable via REST API and MCP tools for agent consumption.

## Rationale

Different risk methods produce different valid reporting forms. A graph-native risk platform also needs to aggregate by operational asset context, not only by abstract risk category.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#263` (GC-T008: Risk Aggregation & Reporting)
