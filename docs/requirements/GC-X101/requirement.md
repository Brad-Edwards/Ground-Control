---
id: GC-X101
title: "Severity classification of Codex review findings"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-05-09T18:38:22.051656Z
updated_at: 2026-05-09T18:38:22.051656Z
---

# GC-X101 — Severity classification of Codex review findings

## Statement

Every finding emitted by `gc_codex_review` shall carry a structured severity classification consisting of (a) an IEEE 1044-2009-aligned severity class drawn from the enumerated set {Blocking, Critical, Major, Minor}, and (b) for findings whose root cause is a security vulnerability, an additional CVSS v4.0 Base vector string and numeric score in the 0.0–10.0 range. The classification shall be produced by the reviewer model against a rubric that includes at least two anchored example findings per severity class, supplied as part of the review prompt. Findings the rubric cannot place into a class shall be returned as `Minor` with an explicit `unclassified=true` flag rather than the reviewer guessing. The classification shall be persisted in the durable findings record posted to the issue thread per ADR-029, and shall be the input format consumed by GC-X102, GC-X103, GC-X104, and GC-X105.

## Rationale

Existing review output has no structured severity attribute; downstream stopping decisions cannot be principled without one. IEEE 1044-2009 defines the qualitative classes but prescribes no numeric weights; CVSS v4.0 is the standard for security findings. Anchored examples per class are the strongest stabilizer for LLM ordinal grading per the Autorubric / LLM-Rubric literature. Inter-rater reliability is empirically poor for severity (Tian, Ali, Lo, Hassan EMSE 2016 — 28.9–50.8% disagreement on duplicate-bug severity in OpenOffice / Mozilla / Eclipse) so absolute scores must not be over-trusted, but having a structured class on every finding is the prerequisite for the rest of the model to function.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/031-codex-review-stopping-model.md` (ADR-031: Severity Rubric and Stopping Model for Pre-Push Codex Review)
- DOCUMENTS → GITHUB_ISSUE `817` (Severity rubric for Codex review findings + pre-declared exit gates)
