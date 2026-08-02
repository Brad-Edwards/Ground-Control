---
id: GC-GRC-014
title: "Adversarial GRC Completeness Review"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:26:27.012155Z
updated_at: 2026-07-12T16:35:41.562855Z
---

# GC-GRC-014 — Adversarial GRC Completeness Review

## Statement

An independent reviewer pass shall check GRC completeness for every security-relevant change.

(a) The reviewer shall receive the derived facts, the proposed/updated model, and the diff, and answer one question: what does this change introduce that the model does not capture?

(b) The pass shall run pre-push in the implementation loop and as a verification stage in the assessment lane.

(c) Findings shall follow the existing review-loop disposition contract (fix / wontfix-with-user-authorization / not-applicable-with-rationale) with durable findings and decision records on the issue thread.

(d) The reviewer is a checker over derived facts, not a second generator: its findings must reference specific facts, model elements, or diff hunks.

## Rationale

Deterministic rules set the floor; the implementing agent works above it; the adversarial reviewer covers what both missed. This mirrors the proven codex/test-quality pre-push reviewer pattern, applied to model completeness — the one place residual LLM judgment genuinely earns its keep.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1127` (Issue #1127: GC-GRC-014 adversarial GRC completeness review)
