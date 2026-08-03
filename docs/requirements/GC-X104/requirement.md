---
id: GC-X104
title: "Independent-reviewer confirmation for Critical findings"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-05-09T18:38:48.950202Z
updated_at: 2026-05-09T18:38:48.950202Z
---

# GC-X104 — Independent-reviewer confirmation for Critical findings

## Statement

A finding classified `Critical` or `Blocking` per GC-X101 shall not gate `/implement` workflow termination decisions, escalation triggers, or the issue-thread record's blocking-status rendering until it has been confirmed by a second independent reviewer-model invocation. Independence requires either a different model family from the original reviewer or a separately-spawned session with no shared context with the original reviewer. Both reviewer outputs shall be persisted in the issue thread findings record. If the second reviewer disagrees on classification, the lower severity prevails for gating decisions but both classifications are retained in the audit record. The independent confirmation step shall not consume an additional `gc_codex_review` cycle against the per-issue cap.

## Rationale

Recent LLM-as-judge research documents systematic overcorrection bias: arXiv 2508.12358 (2025) shows that LLMs prompted to explain and propose fixes flag conforming code as defective at significantly higher rates, and richer prompts make this worse; arXiv 2603.18740 shows adversarial framing flips review verdicts in 88.2% of cases. Capture-recapture inspection methodology (Briand, El Emam, Freimut, Laitenberger IEEE TSE 26(6), 2000) uses overlap between independent reviewers to estimate remaining defects — the same independence assumption is what makes a single judgment of `Critical` more or less trustworthy. A single LLM declaring `Critical` is the highest-leverage point for false positives in the loop; cross-model confirmation absorbs the bulk of LLM-judge bias before that classification gates anything user-facing.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/031-codex-review-stopping-model.md` (ADR-031: Severity Rubric and Stopping Model for Pre-Push Codex Review)
- DOCUMENTS → GITHUB_ISSUE `819` (Independent-reviewer confirmation for Critical and Blocking findings)
