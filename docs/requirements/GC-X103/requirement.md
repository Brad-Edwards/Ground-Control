---
id: GC-X103
title: "Severity-weighted early-stop within Codex review cycle cap"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-05-09T18:38:40.799639Z
updated_at: 2026-05-09T18:38:40.799639Z
---

# GC-X103 — Severity-weighted early-stop within Codex review cycle cap

## Statement

After each completed `gc_codex_review` cycle, the workflow shall compute a severity-weighted finding score for that cycle (weights: Blocking=10, Critical=10, Major=5, Minor=1) and compare it against the prior cycle's score. If cycle N's weighted score is strictly less than 25% of cycle N-1's weighted score AND cycle N introduced no `Critical` or `Blocking` finding, the review loop shall terminate without consuming further cycles even if neither the per-issue three-cycle cap nor the GC-X102 exit gates have been reached by their other criteria. The decay calculation, the cycle-by-cycle weighted scores, and the early-stop decision shall be recorded in the issue thread findings record. The 25% threshold and the {10,10,5,1} weights are engineering conventions and are not derived from IEEE 1044, CVSS, or Capers Jones's defect work; the threshold may be tuned per-project via `.ground-control.yaml` in a future revision.

## Rationale

Empirical inspection literature (Porter, Siy, Mockus, Votta ACM TOSEM 1998; Biffl & Halling IEEE TSE 2003) shows diminishing returns past the second independent pass; Self-Refine (Madaan et al. NeurIPS 2023) shows iterative LLM refinement plateaus at 2-4 iterations. A 25% threshold matches the lower bound of the empirical 25-50% per-pass detection-rate decay reported in inspection studies. Deltas across cycles are more reliable than absolute severity counts because the inter-rater unreliability documented in GC-X101's rationale (~30-50% per Tian et al. 2016) cancels out under differencing. This requirement directly addresses the "cycle 2 was minor but we ran cycle 3 anyway" failure mode.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/031-codex-review-stopping-model.md` (ADR-031: Severity Rubric and Stopping Model for Pre-Push Codex Review)
- DOCUMENTS → GITHUB_ISSUE `818` (Severity-weighted early-stop + structured cycle-3 escalation decision aid)
