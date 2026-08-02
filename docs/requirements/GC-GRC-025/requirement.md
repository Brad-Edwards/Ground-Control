---
id: GC-GRC-025
title: "Polyglot Coverage Matrix and Explicit Declination"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:27:54.771912Z
updated_at: 2026-07-12T16:35:41.563756Z
---

# GC-GRC-025 — Polyglot Coverage Matrix and Explicit Declination

## Statement

The derivation layer shall maintain a per-project language/surface coverage matrix.

(a) The matrix shall record which adapters ran against which surfaces, and which surfaces are underivable with current adapters.

(b) Underivable surfaces shall require either declared boundaries/models (GC-GRC-004) or a recorded, user-visible declination; declinations are first-class artifacts visible in screening records and workspaces.

(c) Gates shall treat undeclared underivable surfaces as gaps, not passes.

(d) The matrix shall update as adapters are added, automatically converting prior declinations into derivable scope flagged for reassessment.

## Rationale

Ground Control consumers are polyglot; honesty about where derivation cannot see is what separates a trustworthy gate from a false sense of coverage. The capture-limits discipline mirrors the ACES inventory methodology's explicit-declination principle.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1138` (Issue #1138: GC-GRC-025 polyglot coverage matrix and explicit declination)
