# ADR-031: Severity Rubric and Stopping Model for Pre-Push Codex Review

## Status

Proposed

## Date

2026-05-09

> **Amended by issue #906 (2026-05-13):** The "three pre-push cycles per issue" baseline this ADR builds on is now a **configurable default of 1 cycle**. The cap value lives on the MCP tool as `CODEX_REVIEW_PREPUSH_HARD_CAP` and is overridden per-repo via `.ground-control.yaml::workflow.codex_review.pre_push_cap` (bounds `[1, 10]`). Repos that want the historical 3-cycle baseline this ADR describes set the knob explicitly. The severity rubric, stopping model, and `override_cap` escape semantics this ADR proposes are **unchanged**; only the default-cap-value assumption shifts. Empirical observation behind the drop: cycles 2–3 historically compounded the agent's own fix-introduced bugs more than they caught net-new bugs (e.g., PR #903's 4-cycle run), and the catch-rate-vs-loop-cost tradeoff favors cycle-1 + CI / SonarCloud / human review for the typical diff. The "Sometimes a run goes 5+ cycles deep with real bugs every cycle" failure mode below still benefits from the `override_cap` escape; the "Sometimes a run reaches cycle 3 with all-Minor cosmetic findings" failure mode is moot under cap-1 (the cycle 3 boundary doesn't exist by default).

## Context

GC-O007 (amended by ADR-029) caps `gc_codex_review` at three pre-push cycles
per issue and routes any "concern remaining after cycle 3" to a user-facing
escalation comment. Empirically, two failure modes show up at the cap
boundary:

1. Sometimes a run goes 5+ cycles deep (with `override_cap=true`) and is
   still surfacing real bugs every cycle. The override exists for exactly
   this case and works.
2. Sometimes a run reaches cycle 3 with all-`Minor` cosmetic findings and
   the user is asked to authorize cycle 4 anyway, because the existing
   workflow has no notion of "this round was so trivial we should have
   stopped at cycle 2." The user's input is "vibes on whether the last
   round of findings was bad enough to warrant another."

The problem is structural: the workflow has a cycle cap but no severity
classification on findings, no pre-declared exit criteria for a given run,
no within-cap early-stop signal, and no cross-model confirmation on the
highest-impact (`Critical`) findings. With none of those, the user's
terminal authorization at cycle 3 is unavoidably gut-feel.

The established software-engineering literature on this problem is mature.
Inspection-era work prescribes pre-declared numeric exit gates (Fagan IBM
Sys.J. 1976; Gilb & Graham, *Software Inspection*, 1993). Empirical studies
of inspection effectiveness show diminishing returns past two independent
passes (Porter, Siy, Mockus, Votta ACM TOSEM 7(1), 1998; Biffl & Halling
IEEE TSE 29(5), 2003). Capture-recapture defect-population estimation uses
overlap between independent reviewers (Briand, El Emam, Freimut,
Laitenberger IEEE TSE 26(6), 2000). Cost-benefit stopping is the framework
that justifies all the others (Freimut, Briand, Vollei IEEE TSE 31(12),
2005; Kemerer & Paulk IEEE TSE 2009).

Two further bodies of work specifically address LLM-judge calibration. The
rubric literature (Autorubric arXiv 2603.00077; LLM-Rubric
arXiv 2501.00274) finds anchored few-shot examples per ordinal class are
the strongest stabilizer. The bias literature documents systematic
overcorrection (arXiv 2508.12358, 2025 — LLMs prompted to find defects
flag conforming code at higher rates; richer prompts make it worse) and
adversarial-framing instability (arXiv 2603.18740 — verdict flips in
88.2% of cases). Severity inter-rater reliability is empirically poor in
human-rated bug data too (Tian, Ali, Lo, Hassan EMSE 2016 — 28.9–50.8%
disagreement on duplicate-bug severity in OpenOffice / Mozilla / Eclipse).
Implication: absolute severity counts are not trustworthy as stopping
signals; deltas across cycles are.

Industry severity standards include IEEE Std 1044-2009 (qualitative
classes, no prescribed weights), CVSS v4.0 (0.0–10.0 numeric for security,
with Base/Threat/Environmental decomposition), Capers Jones DRE work
(Sev-1..Sev-4, no formal weights), and SARIF v2.1.0
(`error`/`warning`/`note`/`none`). DREAD has been retired by Microsoft for
subjectivity. None of these prescribes the {10,5,2,1}-style multiplicative
weights commonly attributed to them; those are engineering convention.

## Decision

Adopt a five-piece stopping model that refines GC-O007 without superseding
it or weakening the existing cap. Each piece is a separate refining
requirement so they can be implemented and tested independently.

### 1. Severity classification on every finding (GC-X101)

Every `gc_codex_review` finding carries an IEEE 1044-2009-aligned class
from `{Blocking, Critical, Major, Minor}`, plus a CVSS v4.0 Base vector +
numeric score for security findings. The reviewer's prompt includes ≥2
anchored example findings per class. Findings the reviewer cannot place
are returned as `Minor` with `unclassified=true` rather than guessed.

### 2. Pre-declared exit gates per run (GC-X102)

Each `/implement` run declares numeric gates before cycle 1 —
`max_blocking=0`, `max_critical=0`, `max_major=N`,
no-new-categories-in-final-cycle — recorded as a marker block in the plan
comment. Meeting all gates terminates the loop early; missing them at the
existing three-cycle cap triggers escalation.

### 3. Severity-weighted early-stop within the cap (GC-X103)

After each cycle, compute a weighted score (Blocking=10, Critical=10,
Major=5, Minor=1) and compare to cycle N-1. If cycle N's score is strictly
less than 25% of cycle N-1's AND no `Critical`/`Blocking` was introduced,
terminate without further cycles. The 25% threshold matches the lower
bound of the empirical 25-50% per-pass detection-rate decay reported in
inspection studies. Weights and threshold are engineering convention, not
standards-prescribed.

### 4. Independent-reviewer confirmation for `Critical`/`Blocking` (GC-X104)

A `Critical` or `Blocking` finding does not gate workflow termination,
escalation, or "what blocks merge" rendering until confirmed by a second
independent reviewer-model invocation (different model family or
fresh-context session). Disagreement on classification → lower severity
prevails for gating, both retained in audit. Confirmation does not consume
a `gc_codex_review` cycle against the cap.

### 5. Structured cycle-3 escalation decision aid (GC-X105)

When a run hits cycle 3 with gates not satisfied, the escalation comment
includes severity-weighted scores per cycle, decay ratios, category-novelty
signals, projected cycle-4 yield, count of unconfirmed `Critical` findings,
and a recommended action from `{approve_cap_override,
accept_remaining_findings_as_wontfix_with_rationale,
stop_run_and_open_new_issue}` with supporting signals. Decision authority
remains the user's; the requirement is that the decision input be
structured signal rather than free-text vibes.

### Invariants preserved

GC-O007's cap mechanics are unchanged. The override-cap path stays
available for legitimate "I see this is still finding real things" cases.
The five additions sit *inside* the cap, not in place of it.

The reviewer-of-record invariant (ADR-027 / ADR-029) is preserved: review
tools always route through `gc_codex_review`, `gc_codex_verify_finding`,
`gc_codex_architecture_preflight`. The independent-confirmation reviewer
(GC-X104) is invoked through the same MCP boundary; it is not a new direct
GitHub or LLM client.

Tool-layer enforcement boundary from ADR-029 is preserved: the MCP server
is the enforcement point for severity classification, weighted-score
computation, exit-gate evaluation, second-reviewer confirmation, and the
escalation decision-aid marker. Skills do not duplicate this in prose; the
workflow contract is enforced where the reviewer outputs are processed.

The 25% threshold and `{10,10,5,1}` weights are stated as engineering
convention. They may be tuned per-project via `.ground-control.yaml` in a
future schema revision once telemetry from real runs justifies the
per-project knob; the initial implementation hard-codes them.

## Consequences

### Positive

- The cycle-3 escalation prompt becomes structured signal (decay ratio,
  category novelty, projected yield, unconfirmed-Critical count,
  recommended action) instead of "should I do another cycle?" with no
  inputs. This was the explicit user-pain motivating the work.
- Within-cap early-stop on severity decay eliminates the "cycle 2 was
  trivial but we ran cycle 3 anyway" failure mode.
- Independent confirmation of `Critical` findings absorbs the bulk of
  LLM-judge overcorrection bias (arXiv 2508.12358) before that
  classification gates anything user-facing — the highest-leverage point
  for false positives in the loop.
- Pre-declared exit gates make termination criteria a property of the run,
  recorded in the issue thread per ADR-029, rather than an undocumented
  in-run agent judgment.
- Per-finding severity class is the input format that GC-X100 (fix-the-class
  instruction injection) can also consume, so the two requirements compose
  cleanly.
- Aligns the workflow with the strongest empirical priors from the
  inspection literature (2-3 passes captures the bulk; further passes are
  exceptional) and the LLM-judge calibration literature (anchored examples
  per class are the strongest stabilizer).

### Negative

- Five new refining requirements add surface area that must be implemented
  and kept consistent. Partial implementation is worse than none for
  GC-X103 and GC-X105 (both depend on GC-X101's classification existing on
  every finding).
- Independent-reviewer confirmation (GC-X104) doubles the cost in tokens
  and wall time for any cycle that produced a `Critical` finding. Most
  cycles will not, so steady-state cost increase is small, but worst-case
  is non-trivial.
- `{10,10,5,1}` weights are convention, not standards-prescribed. The ADR
  records this honestly; implementations must not cite the weights as
  IEEE 1044 / Capers Jones / CVSS prescriptions.

### Risks

- **Severity rubric drift.** Anchored examples in the review prompt are
  the highest-leverage stabilizer per the rubric-LLM literature; if the
  examples drift to bad anchors over time, classification quality degrades
  and the rest of the model loses signal. Mitigation: rubric examples live
  in version control alongside the `gc_codex_review` prompt template;
  changes to them are reviewed under the normal `/implement` workflow.
- **Decay-rule false stops.** A 25% threshold could plausibly stop a run
  that would have surfaced a real `Critical` in cycle 3. The conjunction
  with "no `Critical` introduced this cycle" closes most of that gap, but
  not all. Mitigation: GC-X105's decision aid still runs at cycle 3 if
  gates aren't met, so legitimate continuation cases route through the
  user.
- **Independent reviewer collusion.** If both reviewer-model invocations
  come from the same model family with similar training, they may share
  the bias the second-reviewer step is supposed to correct for.
  Mitigation: requirement language specifies "different model family OR
  separately-spawned session with no shared context"; implementation
  should prefer the former where available.
- **Audit-trail bloat.** Persisting both reviewer outputs for
  `Critical`/`Blocking` findings + structured decision-aid marker blocks
  adds material to issue threads that already get long under ADR-029. The
  marker-block format absorbs most of the parsing cost; human-readable
  rendering remains compact.
- **Per-project tuning pressure.** Threshold and weights are hard-coded in
  the initial implementation. Real telemetry from GC-X103 firing across
  runs may show the 25% threshold is wrong for some project mix. The
  escape valve is `.ground-control.yaml`, but introducing it before
  evidence justifies it adds schema surface that may not pay for itself.

## Related Requirements

- GC-O007 Gated Agentic Development Loop (refined, not amended)
- GC-X100 Codex review fix-the-class instruction (composes with GC-X101)
- GC-X101 Severity classification of Codex review findings
- GC-X102 Pre-declared exit gates for /implement Codex review loop
- GC-X103 Severity-weighted early-stop within Codex review cycle cap
- GC-X104 Independent-reviewer confirmation for Critical findings
- GC-X105 Structured cycle-3 escalation decision aid

## Related ADRs

- ADR-021 Gated Agentic Development Loop (the base contract this refines)
- ADR-029 Issue-Thread Gate Model (durable record + tool-layer enforcement
  boundary preserved)
- ADR-027 Agent-Neutral Implement Workflow Packaging (reviewer-of-record
  invariant preserved)

## Amendments

**2026-05-19 (issue #931): verdict envelope replaces the findings-only tail.**
Codex now emits a JSON object inside `===REVIEW===...===END===` containing
`verdict` (`ship` | `ship-with-fixes` | `don't-ship`), required non-empty
`architectural_read`, `blocking[]` (the validated finding objects this ADR
documents, plus a required `sweep_evidence` field on one-off classifications
and an optional `structural_blocker` boolean), and optional `notes[]` capped
at 2. Cycle stopping and override semantics are unchanged. The principal-
engineer motivation: a clean review now returns `verdict: ship` as a
first-class outcome rather than the reviewer being structurally pushed to
manufacture findings. See issue #931 and the preflight note at
`architecture/notes/ai-review-recalibration-preflight.md`.

**2026-05-21 (issue #937): codex review runs as an async job.** `gc_codex_review`
and the `gc_codex_review_cycle` wrapper gain an opt-in `async` mode: the tool
spawns the `codex exec` child as a background job and returns a `job_id`
immediately; the new `gc_codex_job` tool polls for the verdict envelope or
cancels the job (the cancel aborts an `AbortController` whose signal kills the
child, so nothing is orphaned). Run synchronously, the call blocked past the
MCP client's per-call timeout and the client abandoned it while the child ran
on (issue #893). The stopping model this ADR defines is **unchanged** — the
per-issue cycle cap, the `gc:codex-review-cycle` marker family, the
`override_cap` semantics, and the `verdict: ship` clean outcome all behave
exactly as before. Async changes only how the agent waits for a cycle's
result, never when the loop stops. See ADR-036 (amendments) for the job model.

**Amendment — renderer summary byte caps (#964).** `gc_render_pr_body` and `gc_post_final_report` now enforce reject-not-truncate byte caps on their caller-controlled summary fields. `gc_post_decision_record` — the per-cycle decision-record surface this ADR's stopping model writes to — is unchanged at the schema layer; its caller-controlled prose fields (`notes[].text`, finding rationales, titles) already had per-field caps. The canonical succinctness rule lives in `skills/implement/steps/_review-loop-rules.md § Update succinctness (canonical)`.
