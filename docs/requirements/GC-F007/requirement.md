---
id: GC-F007
title: "Target Assurance Level Attribute"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 3
created_at: 2026-03-14T18:37:47.395727Z
updated_at: 2026-03-14T18:37:47.395727Z
---

# GC-F007 — Target Assurance Level Attribute

## Statement

The system shall support a target assurance level attribute on requirements (L0, L1, L2, L3 per ADR-012), defaulting to L0. The target assurance level shall be settable at creation and updatable. Verification gap analysis (GC-F003) shall use this attribute as the comparison target against achieved assurance level.

## Rationale

GC-F003 specifies gap analysis comparing target vs. achieved assurance, but no requirement defines the target as a settable requirement attribute. Without it, the gap analysis has nothing to compare against. ADR-012's decision table provides classification defaults, but projects need to override per-requirement based on risk classification.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#237` (GC-F007: Target Assurance Level Attribute)
