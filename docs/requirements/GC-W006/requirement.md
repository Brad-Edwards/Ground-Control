---
id: GC-W006
title: "Value of Information Analysis"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-04-11T19:00:48.105824Z
updated_at: 2026-04-11T19:00:48.105824Z
---

# GC-W006 — Value of Information Analysis

## Statement

The system shall compute Expected Value of Perfect Information (EVPI) and Expected Value of Sample Information (EVSI) for decision variables, identifying which uncertain inputs, if resolved, would most change the optimal decision and by how much. VOI results shall be expressed in the same units as the decision outcome (dollars, hours, or user-defined) so that the cost of investigation can be compared directly to the value of the information gained. The system shall support 'should we spike or investigate before committing' decisions by comparing investigation cost against VOI.

## Rationale

Teams routinely either over-investigate (analysis paralysis) or under-investigate (premature commitment) before making decisions. Value of Information analysis resolves this by computing whether gathering more data is worth the cost. If EVPI for a variable is $500 but investigating it costs $5,000, skip the investigation. If EVPI is $500,000, the spike is obviously justified. This is the meta-decision framework that makes all other analysis efficient.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#784` (GC-W006: Value of Information Analysis)
