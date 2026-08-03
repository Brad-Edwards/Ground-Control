---
id: GC-R009
title: "Third-Party Risk Reporting"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T16:55:43.355623Z
updated_at: 2026-03-30T02:56:40.990988Z
---

# GC-R009 — Third-Party Risk Reporting

## Statement

The system shall support aggregate third-party risk reporting including vendor risk tier distribution, overdue assessments, expiring contracts, control effectiveness trends, exception tracking, dependency concentration across critical operational assets, and other methodology-aware summaries. Reports shall be queryable via REST API and MCP tools.

## Rationale

Aggregate vendor risk posture is a board-level metric. Graph-native third-party reporting should surface not only vendor scores but also which critical services, integrations, or data dependencies create concentration risk.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#208` (GC-R009: Third-Party Risk Reporting)
