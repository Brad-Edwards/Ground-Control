---
id: GC-X102
title: "Pre-declared exit gates for /implement Codex review loop"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-05-09T18:38:30.655673Z
updated_at: 2026-05-09T18:38:30.655673Z
---

# GC-X102 — Pre-declared exit gates for /implement Codex review loop

## Statement

Each `/implement` run shall declare numeric exit gates before cycle 1 of `gc_codex_review`. Exit gates shall include at minimum: (a) `max_blocking = 0` and `max_critical = 0` for new findings in the final cycle, (b) `max_major` threshold (default 0; configurable per run via the plan comment), (c) the constraint that any finding category surfaced in cycle N shall already have appeared in cycle N-1 (no new category in the terminal cycle), and (d) the existing per-issue three-cycle hard cap from GC-O007. The declared gates shall be recorded as a marker block in the plan comment posted to the issue thread per ADR-029 so that the gate values used for a given run are auditable. A run whose latest cycle satisfies all declared gates shall terminate the review loop without consuming further cycles; a run that hits the cycle cap without satisfying its declared gates shall trigger the escalation path defined by GC-O007 and GC-X105.

## Rationale

Gilb & Graham (Software Inspection, 1993) and Fagan (IBM Systems Journal, 1976) both prescribe pre-declared numeric exit criteria as the discipline that makes inspection terminate honestly rather than by feel. Today the workflow only has the cycle cap — there is no concept of "this run's done conditions" before cycle 1, so the agent can't terminate early with confidence and the user can't audit what threshold was implicitly applied. Declaring gates up front in the plan comment makes the stopping criterion a property of the run, not of the agent's vibes during the run.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/031-codex-review-stopping-model.md` (ADR-031: Severity Rubric and Stopping Model for Pre-Push Codex Review)
- DOCUMENTS → GITHUB_ISSUE `817` (Severity rubric for Codex review findings + pre-declared exit gates)
