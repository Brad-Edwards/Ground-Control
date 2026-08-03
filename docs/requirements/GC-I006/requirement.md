---
id: GC-I006
title: "Unified Control Framework Support"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T16:56:42.134309Z
updated_at: 2026-07-12T23:08:15.210253Z
---

# GC-I006 — Unified Control Framework Support

## Statement

The system shall support importing and mapping unified control framework taxonomies (Secure Controls Framework, Unified Compliance Framework) as the canonical control-to-framework mapping layer, enabling automatic derivation of per-framework compliance posture from a single unified control set.

## Rationale

SCF and UCF provide pre-built mappings between controls and 100+ frameworks. Importing these taxonomies gives agents immediate cross-framework mapping without manual per-control mapping — a massive efficiency gain for multi-framework compliance.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#221` (GC-I006: Unified Control Framework Support)
