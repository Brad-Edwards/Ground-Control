# ADR-059: The engineering contract

## Status

accepted

## Date

2026-06-06

## Context

The current `/implement` workflow relies on honorific prose in places where it
needs an operational quality standard. For example, planning guidance asks for
the concerns a senior engineer would have. That phrase does not define what to
generate, what to test, or what a reviewer should reject.

The workflow needs one quality contract that serves two purposes:

1. generation guidance before code is written; and
2. the review rubric after code is written.

Using one contract prevents a common drift pattern: the implementation step
optimizes for one implied standard while the review step applies another. The
contract must map each property either to a deterministic gate or to a reviewer
question grounded in a named artifact such as a requirement, plan, Architecture
Decision Record (ADR), interface contract, or gate manifest. The question must
never be "is this good?"

## Decision

The workflow adopts an **engineering contract** as the operational quality
specification for `/implement`. The contract is injected during generation and
reused unchanged as the review rubric. It is a single source of truth for what
the workflow means by acceptable engineering quality.

The contract contains these observable properties:

| Property | Operational target | Checked by |
|----------|--------------------|------------|
| Interface-first | A contract artifact precedes implementation; every new public symbol is exercised through the public surface. | `contract` phase marker, plan-to-diff reconciliation, new-public-surface classifier, and tests tied to the declared contract. |
| Whole-system fit | The change uses existing cross-cutting helpers, boundaries, and data models; it does not reimplement envelopes, logging, authorization, traceability, or validation helpers. | Architecture gate, anti-hand-roll policy rules, architecture reviewer question grounded in `.ground-control.yaml` vocabulary and binding ADRs. |
| Right-sized simplicity | The implementation is the smallest design that satisfies the requirement and does not add scope outside the plan. | Complexity and size gates, plan-to-diff reconciliation, scope-expansion refusal, and reviewer question grounded in the posted plan. |
| Realistic defensive coding | Inputs are validated at system boundaries; state transitions, security boundaries, and corruption-prone mutators carry explicit contracts and tests. | ADR-057 assurance-classifier gate and the `contract_boundary` capability in ADR-058. |
| Test strength | Tests fail when the implementation is broken, not only when lines are executed. | Mutation threshold, diff coverage threshold, property tests where classified L2, and test-strength reviewer question grounded in the contract. |
| Secure from the gate | The first draft avoids injection, server-side request forgery, unsafe deserialization, missing authorization, secret exposure, and insecure dependency changes. | Static application security testing, secret scan, dependency-policy gates, and security reviewer question grounded in a Common Weakness Enumeration (CWE) or attacker-model artifact. |
| Architectural conformance | The diff stays inside declared boundaries and respects binding ADRs, package rules, and graph or traceability invariants. | Architecture-as-code gate, ADR citation in the plan, phase-marker prerequisites, and architecture reviewer question grounded in the binding ADRs. |
| Extensibility seam | The design names the next expected variation and leaves the smallest useful seam for it. | Reviewer question grounded in the plan and interface contract. This property is not a free-form improvement request. |

Every property must have exactly one primary acceptance path:

- deterministic gate, when the property can be checked by command or MCP
  refusal; or
- reviewer question, when the property needs judgment.

Reviewer questions must cite the artifact they evaluate. Valid examples:

- "Does the diff implement every obligation in the posted contract?"
- "Does any new helper violate ADR-027's configuration boundary?"
- "Does the test suite kill or explicitly explain surviving mutants for the
  changed behavior?"
- "Does the architecture leave the variation named in the plan without adding a
  second abstraction?"

Invalid examples:

- "Is this production quality?"
- "Would a senior engineer accept this?"
- "Can this be better?"

The contract is injected before implementation. It appears in the planning and
contract phases, guides test design, and constrains implementation. The same
contract is then passed to the correctness, security, architecture, and
test-strength review lenses. Reviews report gaps against the contract, the
requirement, the plan, and the binding ADRs. They do not request unrelated
style preferences or speculative improvements.

The contract composes with ADR-057 and ADR-058:

- ADR-057 classifies surfaces that require contracts, property tests, or proof.
- ADR-058 binds each deterministic property to concrete commands or MCP
  refusals.
- This ADR defines the property table that generation and review both use.

## Consequences

The workflow replaces vague quality prose with an artifact that can be used by
both implementation and review. The model receives the same quality target that
the reviewers and gates later apply.

Review findings become easier to adjudicate because each finding must name the
contract property, the governing artifact, and the failed gate or reviewer
question.

The contract limits overcorrection. Reviewer lenses must flag gaps against the
stated requirement and contract; they do not chase unrelated improvements.

Some properties remain partly judgment-based. The workflow must keep those
questions grounded in artifacts and continue to graduate recurring reviewer
findings into deterministic gates when possible.

## References

- ADR-021: Gated Agentic Development Loop.
- ADR-057: Language-neutral assurance ladder and classifier.
- ADR-058: Gate capability-to-provider indirection and gate packs.
- ADR-061: Governable phase-marker state machine.
