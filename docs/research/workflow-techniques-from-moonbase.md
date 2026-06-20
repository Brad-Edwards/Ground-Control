# Workflow Techniques from the Moonbase Engagement: Integration Note

Captured so we can decide whether and how to fold them into the Ground Control
workflow. This is reasoning and a set of open questions, not a specification.
Each adopted item becomes its own requirement (GC-…) with its own design.

## Context

"Moonbase" was a from-scratch build of a large multi-service platform driven
almost entirely by orchestrated background coding agents: one orchestrator that
plans, fans work out to agents on disjoint paths, and independently re-verifies
every agent's output before committing. It ran long enough (design through three
deployed phases, then a design-only track) to surface which orchestration
techniques hold up and which failure modes recur. This note distills the ones
that earned their keep and asks, for each, whether Ground Control already covers
it, should adopt it, or should spike it.

Ground Control already has much of the scaffolding these techniques want: the
requirements graph with IMPLEMENTS and TESTS links, the 16-step development
workflow with an S8 completion gate, `make policy` guardrails, ArchUnit
dependency enforcement, OpenJML ESC in `make verify`, and the codex and
test-quality review skills. So most of what follows is wiring an existing
capability into the workflow at the right point, not building something new.

## The techniques

### 1. Executable design skeletons as the design-to-implementation handoff

In moonbase the rule was that a surface is not designed until an implementer can
make a failing conformance suite pass without taking an architectural decision.
The design deliverable was never prose. It was the contract (schema) plus
header invariants plus a runnable, exported conformance suite plus a reference
implementation proving the suite is satisfiable plus the boundary lint that
fences it. Cheaper models then implemented against the suite. This is what let
design and implementation be done by different (and differently priced) agents
safely.

GC question: should a requirement carry a machine-checkable acceptance artifact
(a conformance suite or executable spec) as a precondition of being ready to
implement, distinct from prose acceptance criteria? Today the requirement graph
links IMPLEMENTS and TESTS after the fact. The proposal is a gate ahead of
implementation: no implementation wave starts until the acceptance suite exists
and fails for the right reason.

Recommendation: spike. This is the highest-leverage item and the one most
aligned with GC's context-aware autonomous engineering thesis. It is the
mechanism that makes autonomous implementation verifiable rather than trusted.

### 2. Structural enforcement over policy

Wherever an invariant could be made a type fact rather than a rule, it was: an
audit record type with no body field (so "never log bodies" cannot be violated,
not merely forbidden); a tool-call union with no direct-action arm (so an agent
cannot act outside the action registry); a service with no delete RPC (so
append-only is structural); dependency-lint denies on forbidden imports.
Reviewers and agents cannot erode what the type system refuses to express.

GC question: GC already enforces `api/ -> domain/ <- infrastructure/` via
ArchUnit and runs `make policy`. Should "prefer structural enforcement" become
an explicit coding standard, with the policy suite growing a check whenever a
recurring invariant could be made structural instead of reviewed?

Recommendation: adopt as a coding-standard principle and let it pull new
ArchUnit and policy rules over time. Low cost, compounding return.

### 3. Interface control documents for inter-module seams

Each cross-module edge got one binding interface control document (ICD):
parties, transport, the per-operation semantics table, invariants with
enforcement points, the failure and partial-failure matrix, security
obligations, and the conformance suite that proves it. The governing rule was
that anything an ICD leaves open is an ICD defect, not implementer discretion.
This is what made parallel two-sided implementation (one agent per side) meet
correctly in the middle.

GC question: GC's graph models IMPLEMENTS and TESTS. Should it gain an INTERFACE
node type (or a doc class) for seams, so a contract between two requirements is
itself a tracked, traceable artifact rather than implied?

Recommendation: worth it specifically when GC orchestrates two agents across one
interface. Spike alongside item 1.

### 4. Formal methods as a risk-scored scalpel, with executable twins

Formal specs were admitted only above a risk score (irreversibility times
concurrency times security, over a threshold). Five specs across the whole
program, not a tax on everything. Critically, each formal spec shipped an
executable twin (a property test enumerating the same state space) so the
property is re-checked on every ordinary test run even where the formal
toolchain is not in CI. One Alloy model caught two real design defects before
any implementation existed.

GC alignment: GC already runs OpenJML ESC in `make verify`. The new ideas are
the explicit risk-score admission rule so formal effort stays a scalpel, and the
executable-twin requirement so the property survives outside the heavyweight
gate.

Recommendation: adopt the risk-score rule as policy. Consider the twin-required
convention for any OpenJML-specified surface.

### 5. Trust remote CI, never local-only green

The most expensive recurring failure was a Docker-based test harness that passed
locally for weeks while failing on CI runners (cold image cache versus warm).
Local `make check` is necessary but not sufficient. The orchestrator's habit
became, after every push, to watch the remote run to a verdict before declaring
done. The completion claim is the remote green, not the local one.

GC question: does the S8 completion gate confirm a remote CI verdict, or only
local `make check` plus `make policy`? If only local, an autonomous run can
believe it is done while CI is red.

Recommendation: make "remote CI green" an explicit element of the completion
gate for agent-driven runs. Cheap, and it prevents a whole class of false done.

### 6. Name the implementer-validated assumptions in every design

Design done without execution hides real-world defects. In moonbase these were
all execution-only discoveries: a Bottlerocket SELinux rule that forbade a
volume-sharing pattern, authorization-schema gaps, and a third-party artifact
that self-reverted in a container. The countermeasure was that every design
document carries an explicit "assumptions to validate against reality early"
section, so the first implementation run knows exactly what to probe before
hardening.

GC question: should the design and spec template (and the requirement record)
carry a first-class "assumptions to validate" field, and should the workflow
schedule that validation early in an implementation wave rather than letting it
surface at hardening time?

Recommendation: adopt. It is a small template change with outsized payoff for
autonomous runs, which cannot improvise around a wrong assumption the way a
human would.

### 7. Exit-scenario discipline

Every unit proved its exit criterion with a scripted, re-runnable scenario on
real infrastructure, not just unit tests. Full-system scenario walks caught
defects no unit test could, such as a silent cross-service data drop only
visible when the whole audit chain was traversed end to end. The scenario was a
committed artifact, re-run on every deploy shape.

GC question: should a wave carry a wave-level executable exit scenario as its
definition of done, distinct from per-requirement tests, and should that
scenario be a tracked artifact in the graph?

Recommendation: spike for waves that span services. Likely overkill for
single-requirement waves.

### 8. Orchestration mechanics that held up

Smaller, concrete patterns for the orchestrator itself. GC is an orchestrator,
so these are directly relevant to its fan-out engine.

- Serialize on shared serialization points, parallelize on disjoint paths.
  Generated code, lockfiles, and workspace manifests are contention points.
  Agents that regenerate shared artifacts must run one at a time, while agents on
  disjoint directories run concurrently. Scaffold the shared files (module
  wiring) once and first, so fan-out agents never touch them.
- Verification authority is separate from the producer. The orchestrator re-ran
  every agent's gates itself before committing. An agent reporting green was
  never sufficient. This pairs with item 5.
- Capture volatile values at production time, not at consumption. A deploy that
  re-derived an image tag from HEAD instead of capturing it at push time pulled
  the wrong image. Hand the value forward; do not recompute it downstream.
- The orchestrator never merges its own PRs. Human merge stayed the gate. Agents
  branch, open PRs to `dev`, and stop.

GC question: which of these are already enforced by GC's orchestrator, and which
are implicit conventions worth making explicit in the workflow engine
(especially shared-state contention detection during fan-out)?

## Suggested next step

If any of this is worth pursuing, the natural path is a small set of GC
requirements. Item 1 (conformance-suite-as-readiness-gate) and item 6
(implementer-validated-assumptions field) are the cheapest, highest-value, and
most GC-native. Both are workflow and template changes that make autonomous
implementation verifiable and defensive rather than trusting. Items 2, 4, and 5
are policy and coding-standard adjustments riding existing machinery. Items 3,
7, and 8 are spikes scoped to multi-service or multi-agent waves.

This note is the input to that decision, not the decision.
