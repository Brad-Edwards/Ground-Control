---
id: GC-R007
title: "Third-Party Control Mapping"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T16:55:35.507784Z
updated_at: 2026-03-30T02:56:40.470935Z
---

# GC-R007 — Third-Party Control Mapping

## Statement

The system shall support mapping controls to third parties and to the third-party assets, integrations, data exchanges, or services they protect, tracking control responsibility, evidence of control operation, and methodology-specific effect on linked third-party risk scenarios and risk records. A third party's overall risk profile shall be derived from linked controls, risk assessments, control evaluations, and supporting observations rather than from a standalone score model disconnected from the core risk semantics.

## Rationale

Vendor and third-party risk management should be a specialization of the platform's risk model, not a separate epistemic island. Reusing the shared control, asset, and assessment model avoids duplicated semantics and conflicting score logic.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#206` (GC-R007: Third-Party Control Mapping)
