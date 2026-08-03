---
id: GC-H005
title: "Threat Disposition Tracking"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T19:32:31.026530Z
updated_at: 2026-07-12T23:08:15.210462Z
---

# GC-H005 — Threat Disposition Tracking

## Statement

The system shall support disposition tracking per threat-model entry or threat scenario scoped to the affected operational asset or boundary, including statuses such as identified, analyzed, addressed, accepted, deferred, or not-applicable, with linked controls, requirements, architecture decisions, and supporting observations or evidence implementing the disposition. Formal organizational risk treatment decisions, risk ownership, and risk appetite evaluation shall be handled in the risk record and treatment-plan model rather than stored only on the threat entity.

## Rationale

Threat models need closure and review discipline, but that is not the same thing as a formal risk register. Separating threat disposition from organizational risk treatment keeps the security engineering workflow usable while preserving methodological correctness and anchoring dispositions to real protected objects.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#265` (GC-H005: Threat Treatment Tracking)
