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
ADR-021, ADR-027, ADR-029, ADR-036, and ADR-062: the GitHub issue thread is the
durable record, `.ground-control.yaml` is the configuration boundary,
privileged GitHub and git side effects stay in the Model Context Protocol
(MCP) server, and pull request merge is the only synchronous human touchpoint.

## Decision

The `/implement` workflow is governed by a phase-marker state machine. The
legal marker chain is:

```text
claimed -> context_loaded -> preflight -> contract -> plan
-> test_red -> impl_green -> gates_green -> reviews_clean
-> published -> remote_gates_green
-> traceability_reconciled -> reported -> closed
```

Each marker is written only by the MCP tool that owns that phase. The tool must
re-verify the phase state before writing the marker. It must not trust the
agent's claim. Examples:

- `claimed` requires a server-side issue claim and branch context;
- `context_loaded` requires the server to resolve repository context,
  requirement context, related artifacts, and binding ADRs through the
  `.ground-control.yaml` boundary;
- `contract` requires the posted interface or behavior contract that ADR-059
  defines;
- `test_red` requires evidence that a relevant test or contract check failed
  before implementation changed the behavior, or a documented non-executable
  carve-out;
- `impl_green` requires the implementation and targeted tests to pass;
- `gates_green` requires `gc_run_gates` from ADR-058 to pass every applicable
  blocking local manifest gate;
- `reviews_clean` requires the convergence model from ADR-031 to return a
  clean verdict or a structured terminal decision;
- `published` requires branch push and pull request publication;
- `remote_gates_green` requires required remote status checks to pass through
  the provider-neutral `remote_status` capability;
- `traceability_reconciled` requires live graph reconciliation against the
  current diff;
- `reported` requires the deterministic final-report renderer;
- `closed` requires the merge-verified close path in ADR-060.

Markers that depend on repository content bind to the content state they
verified. At minimum, `contract`, `plan`, `test_red`, `impl_green`,
`gates_green`, `reviews_clean`, and `traceability_reconciled` include the issue
number, repository identity, base ref, head ref, and diff hash. Markers that
depend on gate configuration also include the gate manifest hash and selected
engine and pack versions. `remote_gates_green` additionally includes the pull
request number, head SHA, required-status-set hash, and provider result ids.

If the diff hash, manifest hash, selected pack versions, head SHA, or required
remote status set changes, the dependent marker is stale. Phase-entry tools
must refuse stale markers with the same structured style as missing markers.

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

Stale marker refusals use this shape:

```json
{
  "ok": false,
  "error": "stale_phase_marker",
  "marker": "gates_green",
  "expected": { "diff_hash": "new", "manifest_hash": "current" },
  "actual": { "diff_hash": "old", "manifest_hash": "current" },
  "next_action": "rerun_gc_run_gates"
}
```

The state machine separates detection, fix, and advance:

- detection tools identify missing state, reviewer findings, gate failures,
  stale markers, provider-missing fallbacks, or lifecycle conflicts;
- fix work addresses the concrete failure;
- advance is decided by a deterministic dispatcher reading structured
  envelopes and marker state.

No agent prose decides whether the workflow may advance, loop, or escalate.
Escalation is a distinct terminal state with its own marker and issue-thread
record. Escalation does not masquerade as an intermediate "ask the user" step,
and it does not erase unresolved marker state.

Provider-specific watchers are optional adapters behind the manifest. The
engine-level watcher is a required-status watcher, for example
`gc_watch_required_statuses`, which reads the manifest, discovers required
remote checks, calls provider adapters where configured, and emits the
provider-neutral result envelope that can write `remote_gates_green`.

This ADR explicitly rejects a dynamic orchestrator for this redesign. The
workflow is a fixed state machine with declared edges. It does not delegate
ordering to a planner that constructs new phases at runtime, does not store
phase state in local files, and does not use telemetry as compliance evidence.
Future Temporal adoption can map these phase tools to activities, but the legal
path remains the marker chain above unless a later ADR changes it.

## Consequences

The legal path becomes the easy path. A run cannot plan without context,
implement without a contract, publish without green local gates and clean
reviews, or report without traceability reconciliation.

The workflow can recover from interruptions because the durable issue-thread
markers tell the next run where to resume and which predecessor is missing.

Remote checks become provider-neutral. The engine waits for required statuses
that the manifest and repository settings define, not for a hard-coded
provider phase.

Markers are harder to forge accidentally because they bind to the diff,
manifest, pack versions, and remote status set that were actually verified.
Any change to those inputs forces re-verification instead of silently reusing a
stale success.

The marker chain adds implementation surface. Each phase tool must verify its
own state, return useful refusal envelopes, and avoid stale markers by binding
where needed to issue, branch, base ref, head ref, manifest hash, diff hash,
head SHA, and required status set.

## References

- ADR-021: Gated Agentic Development Loop.
- ADR-029: Issue-Thread Gate Model.
- ADR-031: Review stopping model.
- ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step
  Telemetry.
- ADR-058: Gate manifest, runner contract, and gate-pack bundles.
- ADR-059: The engineering contract.
- ADR-060: Lifecycle determinism.
- ADR-062: Portable /implement engine, gate-pack registry, and consumer
  adoption model.
