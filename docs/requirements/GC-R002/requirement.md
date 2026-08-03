---
id: GC-R002
title: "Third-Party Risk Profile"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T16:55:15.928248Z
updated_at: 2026-03-30T02:56:40.057190Z
---

# GC-R002 — Third-Party Risk Profile

## Statement

The system shall support a third-party risk profile as a specialized view over linked operational assets, integrations, risk scenarios, risk register records, risk assessment results, control evaluations, and observations for that third party. The profile shall support inherent-style and residual-style summaries, risk tier assignment, business and regulatory context factors, assessment history, and methodology-specific results without introducing a separate parallel definition of risk.

## Rationale

Third-party risk should reuse the core risk model rather than inventing a second score vocabulary. A specialized profile view grounded in the actual third-party dependency surface preserves TPRM workflows while keeping FAIR, NIST, ISO, and enterprise risk semantics consistent.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#201` (GC-R002: Third-Party Risk Profile)
