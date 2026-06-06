# ADR-031: Severity Rubric and Stopping Model for Pre-Push Codex Review

## Status

accepted

## Date

2026-06-06

## Context

ADR-021 and ADR-029 require pre-push review before `/implement` publishes a
pull request. The previous review loop had a mechanical failure: the server
cap evaluators at `mcp/ground-control/lib.js:3891` and `:4382` treated
`nextCycle === hardCap` as the last-in-cap escalation path. With
`pre_push_cap=1`, cycle 1 with any finding became "fix, summarize, and
escalate" instead of "fix, re-review, and continue until clean." The result
was a one-cycle loop that stopped in the middle of convergence.

The workflow also needs to account for large language model (LLM) review
failure modes. Reviewers can overcorrect clean code when prompted to find and
fix defects. arXiv:2508.12358 documents this overcorrection pattern and shows
that two-phase reflective and behavioral-comparison prompts reduce it.
arXiv:2512.02304 documents a verifier-quality threshold: verification helps
only when the verifier is accurate enough and independent enough to reduce
false rejection.

The stopping model must therefore separate detection, fix, and advance. LLM
reviewers may detect findings. The implementer fixes them. A non-LLM
dispatcher decides whether the workflow advances, loops, or escalates from a
parseable verdict envelope.

## Decision

The pre-push review loop is a convergence loop:

```text
review -> if findings exist, fix -> re-review -> repeat
```

The loop ends only when one of these terminal conditions holds:

- a clean verdict envelope is posted and all blocking reviewer lenses report
  `ship`;
- a real cap of at least 2 cycles is reached and the dispatcher emits a
  structured decision aid; or
- an explicit terminal escalation state is recorded.

Cycle 1 must never collapse into escalation because findings exist. A
configured hard cap below 2 is invalid as an effective stopping cap for
issue-anchored `/implement` and `/quickfix` reviewer loops. The parser may
accept legacy values such as `pre_push_cap=1` for compatibility, but the
dispatcher floors the effective cap to 2. Repositories that need a single
advisory review must model that as an advisory lens outside this convergence
gate.

Every reviewer returns a parseable verdict envelope. The envelope includes:

- `verdict`: `ship`, `ship-with-fixes`, or `don't-ship`;
- `reviewer_lens`: `correctness`, `security`, `architecture`, or
  `test-strength`;
- `architectural_read` or the lens-specific equivalent;
- `findings[]`, each with severity, location, evidence, classification
  (`one-off` or `class`), disposition state, and sweep evidence where needed;
- `blocking[]`, derived from findings that block advance;
- `notes[]`, bounded and non-blocking.

The review wrapper returns `next_action` beside the lens envelopes. That
field is computed by the dispatcher, not by reviewer prose.

The dispatcher owns advance. It reads all lens envelopes, `gc_run_gates`
result envelopes from ADR-058, required remote status envelopes, prior cycle
markers, and the cap state. It returns one of:

- `advance_to_next_phase`;
- `fix_findings_and_reinvoke`;
- `post_structured_decision_aid_and_escalate`;
- `record_terminal_escalation`.

Reviewer prose does not decide advance, loop, or escalation.

The dispatcher treats `gc_run_gates` results as the deterministic gate surface.
A blocking gate failure prevents advance until the gate passes or a terminal
escalation is recorded. A `provider_missing` result never becomes a silent
pass. If the manifest declares reviewer fallback for that missing capability,
the dispatcher routes the gap to the matching reviewer lens and records
telemetry such as `provider_missing` and `reviewer_fallback_used`. If the
manifest marks the missing provider as blocking without fallback, the
dispatcher refuses advance.

The review set has four independent lenses:

- **correctness**: checks the requirement, acceptance criteria, contract, and
  plan against the diff;
- **security**: checks trust boundaries, authorization, injection, server-side
  request forgery, unsafe deserialization, secrets, dependency risk, and
  attacker-model obligations;
- **architecture**: checks binding Architecture Decision Records (ADRs),
  package boundaries, cross-cutting helpers, traceability rules, and the
  engineering contract;
- **test-strength**: checks that tests exercise the public contract and are
  backed by `mutation`, `diff_coverage`, or `property_verification` evidence
  where the gate manifest requires it.

Each lens runs in a fresh context with edit tools removed. A lens sees the
requirement, contract, plan, relevant ADR clauses, gate results, and diff. It
does not see the implementation reasoning that produced the diff.

Critical or Blocking findings require independent cross-model confirmation
before they gate advance or escalation. Cross-model review is always available
to the workflow. When the first reviewer and confirming reviewer disagree on
severity, the lower severity controls gating and both opinions remain in the
issue-thread record.

The correctness lens must use an anti-overcorrection prompt structure. It must
first extract requirement obligations and then audit the diff, or it must
summarize expected behavior and actual behavior before comparing them. The lens
flags only gaps against the stated requirement, contract, plan, or binding ADRs.
It does not request unrelated improvements.

The GC-X101 through GC-X105 mapping remains the implementation contract:

- **GC-X101**: every finding carries a severity class (`Blocking`,
  `Critical`, `Major`, or `Minor`) and evidence grounded in the supplied
  artifact set. Security findings also carry a Common Vulnerability Scoring
  System (CVSS) vector when applicable.
- **GC-X102**: each run declares exit gates before cycle 1. Defaults are
  `max_blocking=0`, `max_critical=0`, no unresolved class findings, and no
  unconfirmed Critical or Blocking findings.
- **GC-X103**: the dispatcher computes per-cycle delta, category novelty, and
  severity-weighted trend. These signals can stop a loop early only when all
  blocking gates are satisfied; they cannot advance a dirty loop.
- **GC-X104**: Critical and Blocking findings need independent confirmation.
  Confirmation does not consume a normal review cycle.
- **GC-X105**: when the cap is reached without a clean verdict, the dispatcher
  emits a structured decision aid. The aid includes cycle history, unresolved
  findings, severity trend, new categories, confirmation status, gate results,
  projected value of another cycle, and recommended next action.

The decision aid is not an invitation for agent prose to decide. It is a
terminal escalation artifact posted to the GitHub issue thread per ADR-029.

## Consequences

The workflow regains a real fix-and-re-review band. A finding on the first
cycle causes repair and re-review, not an immediate cap-bound escalation.

Clean review advances mechanically. If all blocking lenses return `ship` and
the dispatcher sees no unresolved gate, the workflow moves to the next phase
without stopping for a human confirmation turn.

Reviewer quality improves because lenses are independent, artifact-grounded,
and constrained to stated obligations. Cross-model confirmation reduces the
risk that one overcorrecting reviewer blocks the workflow with a false
Critical or Blocking finding.

The model adds cost. Fresh-context lenses, cross-model confirmation, and a
minimum two-cycle cap are slower than a single pass. ADR-058's deterministic
gate manifest and ADR-036's routing keep that cost focused on the surfaces
where review adds value.

Partial implementation is unsafe. If severity classes, dispatcher decisions,
and decision aids do not ship together, the workflow can recreate the old
failure with a different vocabulary.

## Related Requirements

- GC-O007 Gated Agentic Development Loop.
- GC-X101 Severity classification of review findings.
- GC-X102 Pre-declared exit gates for review loops.
- GC-X103 Severity-weighted convergence signals.
- GC-X104 Independent confirmation for Critical and Blocking findings.
- GC-X105 Structured escalation decision aid.

## Related ADRs

- ADR-021: Gated Agentic Development Loop.
- ADR-027: Agent-Neutral Implement Workflow Packaging.
- ADR-029: Issue-Thread Gate Model.
- ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step Telemetry.
- ADR-058: Gate manifest, runner contract, and gate-pack bundles.
- ADR-059: The engineering contract.
- ADR-061: Governable phase-marker state machine.
- ADR-062: Portable /implement engine, gate-pack registry, and consumer
  adoption model.

## References

- arXiv:2508.12358, overcorrection in LLM code verification and
  anti-overcorrection prompt structures.
- arXiv:2512.02304, verifier-quality threshold and false-rejection risk.
- Issue #1075, `/implement` workflow redesign.
