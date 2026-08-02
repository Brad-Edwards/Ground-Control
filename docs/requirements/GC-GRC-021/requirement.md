---
id: GC-GRC-021
title: "In-Loop Risk Assessment Integration"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:27:23.078612Z
updated_at: 2026-07-12T16:35:41.563607Z
---

# GC-GRC-021 — In-Loop Risk Assessment Integration

## Statement

Threats confirmed during a change shall produce risk artifacts in the same run.

(a) Each confirmed new threat shall map to a risk scenario — newly created or linked to an existing scenario it instantiates.

(b) Each scenario touched by the change shall carry a methodology-scored assessment (via the project's active methodology profile — FAIR, NIST SP 800-30, or qualitative) sized to the change, with stated assumptions recorded.

(c) A treatment decision shall be recorded for every assessed risk: control implementation in the change (MITIGATE, per GC-GRC-011), or an authorized disposition (ACCEPT et al., per GC-GRC-015).

(d) Full quantitative reassessment of large scopes may be delegated to the assessment lane, with the in-loop record linking to the lane run.

## Rationale

Threat modeling without risk assessment produces unprioritized lists; assessment without treatment decisions produces shelfware. Closing threat → risk → treatment inside the run is what makes the loop a GRC process rather than a modeling exercise, reusing the existing methodology-profile machinery.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1134` (Issue #1134: GC-GRC-021 in-loop risk assessment integration)
