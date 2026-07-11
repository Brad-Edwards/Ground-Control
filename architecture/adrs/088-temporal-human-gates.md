# ADR-088: Temporal Human Gates (Merge Observation and Authorized Operator Signals)

## Status

Accepted

## Date

2026-07-10

## Context

GC-O009 re-implements the `/implement` loop as a Temporal workflow (ADR-028,
ADR-081). Clause (b) fixes the human-gate model: exactly one synchronous human
gate, PR merge (ADR-029), observed from GitHub as the authoritative event and
never a Temporal signal, plus a closed catalog of explicit operator signals
(cancel, retry-from, review-cap disposition) that require authenticated gate
authority and produce audit records. ADR-029 removed the synchronous
plan-approval gate, and nothing here may reintroduce one.

Prior slices built the substrate. Issue #1277 landed the deterministic core
workflow, its typed activity and signal contracts, and the `observeMergeState`
seam (fakes only). Issue #1278 landed the REST and MCP control surface
(`WorkflowExecutionService`, `TemporalWorkflowControlAdapter`,
`gc_workflow_execution`) with project scoping and an interim `ROLE_ADMIN` route
gate. What remained for the human-gate slice (#1279): a real merge-observation
implementation, gate authority and durable audit on operator signals, a
structural guard against gate reintroduction, and a bounded gate-state read model
for the GC-Q016 console.

The architecture preflight
(`architecture/notes/temporal-human-gates-preflight.md`) set the binding
guardrails this ADR records.

## Decision

1. **Merge observation is a polling seam, not a signal.** Merge-gate observation
   is factored into a single-responsibility `MergeObservationPort` ("observe the
   PR merge fact for a resolved repository binding"), implemented by a
   polling-backed `GitHubMergeObservationAdapter` that reuses the existing
   `GitHubClient` (`gh` CLI), so no new GitHub client is introduced. The workflow
   observes the merge fact through the typed `observeMergeState` activity and
   blocks in `awaitMerge` until it reports merged, then advances to Phase E. A
   webhook receiver may later feed the same typed seam as a latency optimization,
   but it must not advance a phase directly outside the workflow. Repository
   coordinates come from the project-resolved binding, never from caller input.

2. **Operator signals require gate authority and are audited append-only.**
   `WorkflowExecutionService` resolves the authenticated actor from `ActorHolder`
   (never a caller-supplied body field) and denies signals from a missing or
   anonymous actor, deny-by-default, before touching the control port. Every
   attempt, allowed or denied, is written to an append-only `operator_signal_audit`
   table (actor, project, workflow and run id, signal type, contract version,
   authorization outcome, bounded reason and fields). Because a Temporal signal is
   not a JPA mutation, Temporal history and Envers cannot carry this trail, so the
   audit write runs in its own `REQUIRES_NEW` transaction and a denied attempt's
   row survives the authorization-exception rollback. The interim gate is
   `ROLE_ADMIN` at the route (`ApiPathMatrix`) plus this service check; both stay
   behind the service so GC-P024 project-scoped gate authority can replace them
   without changing signal names or the MCP tool shape.

3. **The gate set is pinned structurally.** A repo-native `make policy` check
   (`run_gate_set_invariant_check`) asserts the operator-gate set equals the
   workflow contract's on every surface: the workflow `@SignalMethod` set, the
   `OperatorSignalType` enum, the `implement-signals.v1` schema records, and the
   MCP `WORKFLOW_SIGNAL_TYPES` catalog. It fails if a plan or merge-approval token
   appears on any of them. This is the machine-checked guarantee that no
   synchronous gate is added and none is weakened.

4. **Gate state is a bounded read model queried from the workflow.** The workflow
   exposes a `gateState()` query (current phase, outcome, waiting-for-merge,
   escalated phase and reviewer). The control adapter enriches the single-execution
   describe from that query (degrading to null when a closed execution or absent
   worker cannot answer); the bulk list omits it to avoid O(n) queries. The fields
   surface on the REST and MCP read model for the GC-Q016 console, so the console
   never reconstructs state from raw Temporal history, and Temporal Web is never
   the product surface.

## Consequences

- The merge gate now works end to end against real GitHub via polling, and a
  future webhook source is an additive optimization behind the same port.
- Operator actions carry a durable, queryable gate-authority trail independent of
  Temporal retention, satisfying GC-P024 and GC-Q016 audit expectations.
- Reintroducing a plan-approval or merge-approval gate now fails `make policy`,
  not just review.
- The full `GitHubWorkflowPort` adapter (branch, PR, CI, close side effects) and
  the console UI remain out of scope for later GC-O009 and GC-Q016 slices; GC-P024
  gate authority supersedes the interim `ROLE_ADMIN` fallback when it lands.

## References

- GC-O009 (b), GC-P024, GC-Q016
- ADR-028 (Temporal workflow and console program), ADR-029 (one human touchpoint,
  issue-thread durable records), ADR-081 (workflow program ADR), ADR-082 (contract
  surface), ADR-036 (per-step routing)
- `architecture/notes/temporal-human-gates-preflight.md`
