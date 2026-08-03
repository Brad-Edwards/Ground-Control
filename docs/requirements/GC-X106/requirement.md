---
id: GC-X106
title: "Risk-based dynamic composition of the /implement workflow"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-05-13T05:32:57.970184Z
updated_at: 2026-05-13T05:33:11.868330Z
---

# GC-X106 — Risk-based dynamic composition of the /implement workflow

## Statement

The `/implement` workflow shall vary the set of gating, review, and quality-control steps it composes for a given run based on the assessed risk of the change being implemented. The capability shall hold across the full step inventory (codex review, test-quality review, completion-gate scope, SonarCloud cycle handling, cycle caps, escalation thresholds, and any future review/gate step the workflow gains), so that low-risk changes (e.g., a one-line documentation fix, a typo correction, a behavior-preserving rename) traverse a lighter step set than high-risk changes (e.g., a database migration, a security boundary change, a multi-aggregate refactor). The lighter and heavier step sets shall be functionally equivalent in outcome semantics — a passed lighter composition shall mean the same thing about merge-readiness as a passed heavier composition — but shall be calibrated to the cost of escape for the assessed risk class.

The requirement is a capability requirement: it does NOT prescribe the risk-classification mechanism, the signals consumed for that classification (diff size, touched-path categories, semantic analysis, user declaration, agent declaration, prior-cycle findings, or any other input), the granularity of the risk tiers, the per-tier step compositions, or whether classification is automatic, advisory, or user-authored. Those choices are deferred to follow-on requirements and ADRs. The capability requirement constrains only that:

(a) the workflow shall have a documented mapping from a risk classification to a step composition,
(b) the classification used for a given run shall be auditable on the issue thread per ADR-029 (i.e., the durable record shall show which composition was applied and on what basis),
(c) the user shall retain authority to override the classification (in either direction) for any run, with the override and its rationale recorded on the issue thread,
(d) the override authority shall not bypass user-facing safety gates that exist independent of the risk model (e.g., the no-merge constraint in Step 19 of `/implement`), and
(e) the workflow shall remain coherent under risk-classification disagreement: if two signals disagree on risk class, the higher-risk class shall be applied unless the user explicitly overrides per (c).

A future ADR shall define the classification mechanism, the tier set, and the per-tier step compositions. This requirement establishes that such a mechanism is part of the platform's contract — not an optimization, but a capability the workflow shall expose.

## Rationale

The current `/implement` workflow applies the same step set to every change regardless of cost-of-escape, which has two failure modes both of which we've hit empirically: (1) over-gating low-risk changes — a one-line documentation correction or a behavior-preserving rename incurs the same codex review cap, test-quality review, and completion-gate footprint as a multi-aggregate refactor, which burns user attention and compute on disposable findings against trivial diffs; (2) under-gating high-risk changes — a database migration, a security-boundary change, or a cross-aggregate refactor gets exactly three pre-push codex cycles and one test-quality cycle just like everything else, even though the cost-of-escape on those classes of change is orders of magnitude higher than for the typical case. Cost-benefit inspection literature (Freimut/Briand/Vollei IEEE TSE 31(12), 2005; Kemerer & Paulk IEEE TSE 2009) is consistent: the right amount of inspection for a piece of work is a function of the work, not a constant per workflow invocation.

This capability is adjacent to but distinct from GC-X101–GC-X105: those requirements govern stopping criteria *inside* one review loop on one cycle's findings. GC-X106 governs *which* steps are composed into the workflow at all, before any of those cycles run. Both are valid; the user-facing claim of the platform is "the right inspection for the right risk", and that's a two-axis claim — review depth (X101–X105) plus workflow composition (X106). Without X106 the platform optimizes the wrong thing: it tunes how to terminate inspection on a step that may or may not have been the right step to compose for the risk in the first place. With X106 the cost-of-escape signal lands at the right decision point (which steps run) rather than the wrong one (when to stop a step that's already running).

The requirement is deliberately authored as a capability without prescribing classification methodology. The mechanism is a separate decision — diff-shape heuristics, agent-declared risk, user declaration, ML classifier, change-touched-path mapping, or some composition of those — and the platform should not over-commit to one approach before the implementation work explores them. What does need to be committed now is the contract: a `/implement` workflow that varies its step composition by risk is a different platform than one that doesn't, and downstream requirements (per-tier compositions, the audit trail of classification on the issue thread, override authority, mechanism choice) all depend on this capability being declared.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#907` (GC-X106: Risk-based dynamic composition of the /implement workflow)
