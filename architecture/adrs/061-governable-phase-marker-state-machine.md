# ADR-061: Governable phase-marker state machine

## Status

accepted

## Date

2026-06-06

## Context

Issue #1058 proved the marker-to-refusal pattern: a tool writes a phase marker
after it re-verifies state, and the next tool refuses without that marker. The
current workflow uses this pattern only on a few edges, such as preflight before
plan and traceability reconciliation before final report. Other phase
transitions still depend on step prose and can be skipped, repeated out of
order, or abandoned.

The redesigned `/implement` workflow needs a governable, deterministic path
through the whole directed acyclic graph (DAG). It must remain compatible with
ADR-021, ADR-027, ADR-029, and ADR-036: the GitHub issue thread is the durable
record, privileged GitHub and git side effects stay in the Model Context
Protocol (MCP) server, and PR merge is the only synchronous human touchpoint.

## Decision

The `/implement` workflow is governed by a phase-marker state machine. The
legal marker chain is:

```text
claimed -> context_loaded -> preflight -> contract -> plan
-> test_red -> impl_green -> gates_green -> reviews_clean
-> published -> ci_green -> sonar_clean
-> traceability_reconciled -> reported -> closed
```

Each marker is written only by the tool that owns that phase. The tool must
re-verify the phase state before writing the marker. It must not trust the
agent's claim. Examples:

- `context_loaded` requires the server to resolve repository context,
  requirement context, related artifacts, and binding ADRs;
- `contract` requires the posted interface or behavior contract that ADR-059
  defines;
- `test_red` requires evidence that a relevant test or contract check failed
  before implementation changed the behavior, or a documented non-executable
  carve-out;
- `impl_green` requires the implementation and targeted tests to pass;
- `gates_green` requires the blocking manifest gates from ADR-058 to pass;
- `reviews_clean` requires the convergence model from ADR-031 to return a
  clean verdict or a structured terminal decision;
- `published` requires branch push and pull request publication;
- `ci_green` and `sonar_clean` require verified external gate results;
- `traceability_reconciled` requires live graph reconciliation against the
  current diff;
- `reported` requires the deterministic final-report renderer;
- `closed` requires the merge-verified close path in ADR-060.

Every phase-entry tool refuses without its predecessor marker. Refusals use a
stable envelope:

```json
{
  "ok": false,
  "error": "missing_phase_marker",
  "missing": ["contract"],
  "next_action": "run_contract_phase"
}
```

The state machine separates detection, fix, and advance:

- detection tools identify missing state, reviewer findings, gate failures, or
  lifecycle conflicts;
- fix work addresses the concrete failure;
- advance is decided by a non-LLM dispatcher reading structured envelopes and
  marker state.

No agent prose decides whether the workflow may advance, loop, or escalate.
Escalation is a distinct terminal state with its own marker and issue-thread
record. Escalation does not masquerade as an intermediate "ask the user" step,
and it does not erase unresolved marker state.

This ADR explicitly rejects a dynamic orchestrator for this redesign. The
workflow is a fixed state machine with declared edges. It does not delegate
ordering to a planner that constructs new phases at runtime, does not store
phase state in local files, and does not use telemetry as compliance evidence.
Future Temporal adoption can map these phase tools to activities, but the
legal path remains the marker chain above unless a later ADR changes it.

## Consequences

The legal path becomes the easy path. A run cannot plan without context,
implement without a contract, publish without green gates and clean reviews, or
report without traceability reconciliation.

The workflow can recover from interruptions because the durable issue-thread
markers tell the next run where to resume and which predecessor is missing.

The same gate path applies to Claude, Codex, and future drivers because tools,
policy, CI, and MCP refusals own enforcement.

The marker chain adds implementation surface. Each phase tool must verify its
own state, return useful refusal envelopes, and avoid stale markers by binding
where needed to issue, branch, base ref, head ref, and diff hash.

## References

- ADR-021: Gated Agentic Development Loop.
- ADR-029: Issue-Thread Gate Model.
- ADR-031: Severity Rubric and Stopping Model for Pre-Push Codex Review.
- ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step Telemetry.
- ADR-058: Gate capability-to-provider indirection and gate packs.
- ADR-059: The engineering contract.
- ADR-060: Lifecycle determinism.
