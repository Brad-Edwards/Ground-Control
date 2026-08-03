---
id: GC-V004
title: "Remediation Verification"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T19:34:57.703251Z
updated_at: 2026-07-12T23:08:15.210651Z
---

# GC-V004 — Remediation Verification

## Statement

The system shall support remediation verification: after remediation is marked complete, a re-test or re-assessment shall be triggered (manually or via agent workflow) to verify the remediation resolved the root cause. Verified closure requires evidence that the finding no longer exists. Unverified closures shall be flagged.

## Rationale

Closing findings without verification is "trust-based compliance." Re-testing after remediation ensures the fix actually works. This is a standard audit expectation — findings are not closed until remediation is validated.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#282` (GC-V004: Remediation Verification)
