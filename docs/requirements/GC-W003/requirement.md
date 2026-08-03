---
id: GC-W003
title: "Cost of Delay and WSJF Prioritization"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-11T19:00:31.564991Z
updated_at: 2026-07-12T23:08:15.210740Z
---

# GC-W003 — Cost of Delay and WSJF Prioritization

## Statement

The system shall support Cost of Delay (CoD) estimation for product backlog items, computing Weighted Shortest Job First (WSJF) scores from calibrated inputs. CoD components shall include user-business value, time criticality, and risk reduction or opportunity enablement. Job duration shall be estimated independently. All components shall be expressible as probability distributions rather than point estimates, yielding a WSJF distribution per item rather than a single score. The system shall support re-prioritization analysis showing how WSJF rankings change when component estimates are updated, enabling structured scope-change decisions.

## Rationale

Feature prioritization in most teams is driven by opinion, loudest voice, or HiPPO (highest paid person's opinion). Cost of Delay forces explicit economic reasoning — what does it cost us per unit time to not do this — and WSJF normalizes by effort to identify the highest-leverage work. Using distributions rather than point estimates prevents false tie-breaking and reveals when two items are statistically indistinguishable in priority.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#781` (GC-W003: Cost of Delay and WSJF Prioritization)
