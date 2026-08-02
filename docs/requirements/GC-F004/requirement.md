---
id: GC-F004
title: "Proof Staleness Detection"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-13T23:13:28.589535Z
updated_at: 2026-03-13T23:13:28.589535Z
---

# GC-F004 — Proof Staleness Detection

## Statement

The system shall detect when code changes invalidate existing verification results, marking affected results as stale and requiring re-verification.

## Rationale

Verification results are only valid for the code version they were produced against. Code changes can silently invalidate proofs, creating a false sense of assurance.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#707` (GC-F004: Proof Staleness Detection)
