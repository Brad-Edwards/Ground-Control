---
id: GC-GRC-007
title: "Deterministic Threat Enumeration Rules"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:25:25.537016Z
updated_at: 2026-07-12T16:35:41.562714Z
---

# GC-GRC-007 — Deterministic Threat Enumeration Rules

## Statement

The system shall enumerate candidate threats over the derived architecture model using versioned, deterministic rule packs — no LLM in the enumeration step.

(a) A STRIDE-per-element baseline rule set shall evaluate every model element type (process, store, flow, external entity, boundary crossing).

(b) Category rule sets shall cover, at minimum: deployment/pipeline, authentication/authorization, secret handling, untrusted input, data egress, and cryptographic surfaces.

(c) Every candidate threat shall carry the producing rule's identifier and the matched facts, so candidates are explainable and reproducible.

(d) Rule packs shall be versioned and pinned per project; threat-library content (GC-H004) shall be consumable as rule-pack template material.

(e) The LLM's role is confined to downstream curation: confirming, discarding with rationale, or augmenting candidates — never originating the enumeration.

## Rationale

Threagile-style rule engines prove deterministic threat enumeration over an architecture model works without generation. Rules make the floor of coverage testable and reviewable; the LLM adds judgment above the floor instead of being the floor.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1120` (Issue #1120: GC-GRC-007 deterministic threat enumeration rules)
