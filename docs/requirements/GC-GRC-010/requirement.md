---
id: GC-GRC-010
title: "Design-Phase GRC Work in the Implementation Loop"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:25:55.177770Z
updated_at: 2026-07-11T23:43:44.555574Z
---

# GC-GRC-010 — Design-Phase GRC Work in the Implementation Loop

## Statement

Threat modeling, risk assessment, and control selection for a change shall occur at design/plan time, before implementation begins.

(a) The implementation plan shall enumerate the change's GRC deliverables as first-class plan items derived from the screening sets: threats to model or update, risks to assess, controls to select and implement, and stale entities to refresh.

(b) Security work is inherent to the change, not separate work: a plan shall not defer in-scope GRC deliverables to follow-up issues without explicit user authorization recorded as a disposition (GC-GRC-015).

(c) Control selection at design time shall shape the implementation (secure by design), so controls ship in the same change as the feature rather than being retrofitted.

(d) The completion gate (GC-GRC-012) shall verify the planned GRC deliverables actually landed.

## Rationale

Doing the security design before implementation is what prevents rework and retrofitted controls; making GRC deliverables first-class plan items is what makes 'secure by design' a workflow property instead of an aspiration. The no-defer rule mirrors the repo's existing review-loop discipline.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1123` (Issue #1123: GC-GRC-010 design-phase GRC work in the implementation loop)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (validateGrcDeliverablesPlanGate + runPostImplementationPlan (design-time GRC deliverables gate))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_post_implementation_plan grc_deliverables param + description)
- TESTS → TEST `mcp/ground-control/lib.evaluatephaseprerequisite.test.js` (Deliverables/phase-prerequisite gate tests)
