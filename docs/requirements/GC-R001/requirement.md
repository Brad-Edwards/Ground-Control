---
id: GC-R001
title: "Third-Party Entity"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T16:55:15.900909Z
updated_at: 2026-03-14T16:55:15.900909Z
---

# GC-R001 — Third-Party Entity

## Statement

The system shall support a ThirdParty entity with fields: name, identifier, classification (vendor, supplier, partner, contractor), risk tier (critical, high, medium, low), relationship status (prospect, onboarding, active, under-review, offboarding, terminated), primary contact, and contract metadata.

## Rationale

TPRM requires a first-class vendor/third-party entity. Without it, vendor risk is tracked in spreadsheets outside the GRC system, breaking the connected data model that makes agentic GRC valuable.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#200` (GC-R001: Third-Party Entity)
