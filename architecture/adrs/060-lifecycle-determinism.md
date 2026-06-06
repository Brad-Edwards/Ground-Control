# ADR-060: Lifecycle determinism

## Status

accepted

## Date

2026-06-06

## Context

The `/implement` workflow uses GitHub issue labels, branch claims, final
reports, and issue closure to show where work stands. Previous workflow
versions treated some of these actions as prose steps. That creates two
failure modes:

- a label or close action can be forgotten because the agent stops before the
  prose step; and
- an issue can close or stay open for reasons that are disconnected from the
  merge-verified workflow state.

ADR-029 and ADR-036 already require the GitHub issue thread and deterministic
record renderers for durable workflow records. Issue closure also cannot rely
on the `Closes #N` keyword, because that keyword does not fire reliably in
these repositories and does not express the workflow's merge-verification rule.

## Decision

Lifecycle side effects are server-side consequences of verified phase state.
They are not agent-remembered prose.

The `in-progress` label is a lifecycle marker derived from the branch-claim
state:

- the branch-claim tool sets `in-progress` when it successfully claims the
  issue and records the claim marker;
- the final-report or close tool clears `in-progress` after the run reaches the
  reported or closed state;
- escalation does not clear the label unless the escalation terminal state
  explicitly releases the claim.

Startup deconfliction is mandatory. Before an `/implement` run claims an issue,
the server reconciles the issue labels, claim marker, branch, and existing pull
request links. If another live claim already holds the issue, the tool refuses
with a structured envelope:

```json
{
  "ok": false,
  "error": "issue_already_in_progress",
  "missing": [],
  "next_action": "inspect_existing_claim_or_release_it"
}
```

The refusal includes enough claim context for the caller to decide whether the
existing run is active, stale, or user-authorized for release. The agent does
not infer that state from memory.

Issue close is event-driven. A GitHub Action on `pull_request: closed` invokes
the merge-verified `gc_close_issue_after_merge` path for the issue linked to
the merged pull request. The tool verifies the pull request state is `MERGED`
and `merged_at` is non-null before it closes the issue. A scheduled reconciler
is a backstop for missed webhook or workflow events and closes any issue whose
linked pull request is already merge-verified.

The PR body may keep `Closes #N` for GitHub user-interface linkage, but that
keyword is no longer load-bearing. The canonical close path is
`gc_close_issue_after_merge`.

The single human touchpoint remains PR merge. Event-driven close occurs after
the human has merged the pull request and does not add a new synchronous
approval step.

## Consequences

The issue list reflects real workflow state instead of depending on a later
agent step to remember label bookkeeping.

Concurrent agents cannot silently work the same issue. They must reconcile or
release the existing claim before proceeding.

Post-merge issue close no longer requires a user to re-invoke `/implement`
after merging. The same merge-verification gate still protects the close.

Lifecycle state becomes part of the phase-marker system defined in ADR-061.
Implementations must avoid local counters, agent memory, and prose-only label
or close steps.

## References

- ADR-021: Gated Agentic Development Loop.
- ADR-029: Issue-Thread Gate Model.
- ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step Telemetry.
- ADR-061: Governable phase-marker state machine.
