# Contract-Locked Development: The Method

Status: research draft for review (issue #1290). The binding version of this
method will be the ADR authored under issue #1291 after review.

## The problem this method solves

Automated systems (and junior humans) now write most of the code. The
bottleneck is no longer producing an implementation; it is knowing the
implementation is right without a senior reviewer re-deriving every change.
Review does not scale with generation. The only thing that scales with
generation is machine-checked constraint.

The goal is a development method with three properties:

1. **Demonstrable correctness.** Not always provable, but always measured: the
   claim "this implementation is right" is backed by named, machine-checked
   evidence whose own strength is measured.
2. **Quality control.** Wrongness is caught at the boundary where it is
   introduced, by gates that do not depend on the discipline of the party who
   introduced it.
3. **Predictability.** The outcome of handing a work item to an implementer of
   a given capability tier is a distribution you know, because the degrees of
   freedom left to the implementer have been deliberately narrowed.

## The manufacturing frame

Ground Control's design intent is software development, and eventually
operations, run as a manufacturing process: requirements flow to shipped,
traceable parts through a gated line with durable evidence at every station.
CLD is the **quality system** of that process - the discipline that reduces
error and rework by building quality into the process rather than inspecting
it into the parts afterward (Deming's rule: cease dependence on mass
inspection; design the process so defects are hard to make).

The mappings are load-bearing, not decorative:

| Manufacturing | CLD |
|---------------|-----|
| Engineering drawings with tolerances | Contract package: interfaces plus invariants with explicit bounds |
| Design freeze and engineering change orders | Lock levels; breaking-change gate with declared migration records |
| Bill of materials and process routing | Architecture registry: modules, allowed edges, ownership, lock levels |
| Poka-yoke (mistake-proofing the tooling) | Types, typestate, capabilities: illegal states unrepresentable |
| Jigs, fixtures, and gauges | Oracle battery: conformance suites, property tests, reference models |
| Gauge calibration (measurement-system analysis) | Mutation testing: measuring whether the tests can detect wrongness at all |
| Incoming inspection | Schema validation and negative suites at every trust boundary |
| Stop-the-line (andon) | Gate failure blocks merge; zero deferral |
| Traveler / manufacturing execution system | Ground Control: workflow runs, issue-thread records, traceability graph |
| Statistical acceptance of lots | Cleanroom-style certification: seeded-defect catch rates, usage-profile testing |
| Process capability (first-pass yield, rework rate) | First-pass green rate, iterations to green, escape rate per boundary |
| Corrective action (CAPA) | Escaped-defect triage into battery gap, spec gap, or spec error; the fix lands in the process, not just the part |

Two consequences of taking the frame seriously:

**Agents change the price of controls, not the definition of good.** What
works has been known for decades - Cleanroom, correctness by construction,
SPARK's ladder, refinement, lightweight formal methods - and what kept it
niche was labor cost: specialist proof engineers, hand-built conformance
rigs, unaffordable spec authoring. Agent labor makes exactly those controls
cheap. This method therefore spends agent capacity on more formal specs,
more reference models, more conformance machinery - never on more generation
passes or more reviewer agents voting on each other's output. Where the
method uses redundancy it is calibrated instrumentation, not crowds. This is
the interchangeable-parts moment: implementations become swappable parts
precisely because the gauges got cheap.

**Process improvement becomes an operations-research question.** Once every
boundary reports first-pass yield, rework loops, gate cost, and escape rate -
the numbers the evaluation harness (#1297) and the milestone-17
instrumentation (GC-P025, ADR-061) produce - questions like "where should
the expensive gates sit," "which boundaries deserve reference models," and
"what mutation threshold pays for itself" stop being taste. They become
inspection-allocation and quality-economics problems with data, optimized
against rework and escape cost. CLD is the quality system that makes those
variables real; tuning the line against them is the intended end state.

## The core inversion

Conventional development treats implementation as the primary artifact; tests
and docs trail it, written by the same hands. Contract-locked development
(CLD) inverts this: the **contract package** for a boundary is the primary
artifact, authored first, and locked; the implementation is a subordinate,
replaceable artifact whose only definition of done is satisfying the
contract's enforcement battery.

The inversion only holds if three powers are structurally separated. This is
Cleanroom software engineering's central lesson (developers could not test
their own code; an independent team certified quality statistically), restated
for the agent era:

| Power | Who | Authors | May not touch |
|-------|-----|---------|---------------|
| Design authority | Strong model + human review | Contracts, invariants, oracle battery, architecture registry | (Nothing off limits, but changes are versioned, gated events) |
| Implementer | Weaker model, or any human/agent | Implementation code, interior tests | Contracts, oracle battery, policy checks, architecture registry (protected paths) |
| Verifier | Mechanical CI + independent review agents | Verdicts, measurements | The thing being verified |

The separation is enforced by tooling (protected-path CI gates, CODEOWNERS,
approval markers), not by instruction. An implementer that edits an oracle to
make it pass has not gamed the process; it has failed a CI gate.

The economics follow the separation: design-authority work is expensive and
runs on the strongest models plus human attention, concentrated on a small,
stable, legible surface (the contracts). Implementation is cheap and runs on
weaker tiers. Verification is mostly mechanical. This is the same
capability-tier split ADR-036 already routes by; CLD gives the tiers a
principled division of labor instead of a cost-only one.

## The contract stack

Every boundary carries a contract with up to five layers. Each layer is
machine-checked; a layer without an enforcing check does not count as part of
the contract.

| Layer | What it fixes | Typical artifacts | Typical checks |
|-------|---------------|-------------------|----------------|
| 1. Structural | Which modules exist, which may depend on which, who owns what | Architecture registry (module graph, allowed edges, ownership, lock levels) | ArchUnit / depguard / eslint-boundaries, generated from the registry |
| 2. Syntactic | The shape of the interface | Types, OpenAPI, JSON Schema, IDL; committed generated consumers | Drift gate (regenerate-and-diff), breaking-change gate |
| 3. Semantic | What the operations mean | Pre/postconditions, invariants with stable IDs, error taxonomy | Conformance suites, property tests, invariant-to-check inventory |
| 4. Protocol | Legal orderings, concurrency, lifecycles | State machines, session rules, TLA+/Alloy specs for scored risks | Model checking in CI, executable-twin tests, replay tests |
| 5. Policy | Cross-cutting obligations | Authorization matrix as data, redaction rules, resource budgets, confinement rules ("this SDK only in module X") | Policy-as-code checks, negative suites, boundary lint |

"Strong interfaces all the way down" means every boundary has layers 1 and 2
at minimum, and layers 3 to 5 wherever a risk score demands them. It does not
mean every function gets a formal spec. Interior code stays free precisely
because the boundary above it is strong; freedom inside a locked boundary is
the payoff, not a leak.

## Lock levels

Not all boundaries are equal. Each boundary in the architecture registry
declares a lock level, which determines the change protocol, not the layer
count:

- **Locked** (service and module boundaries, published payload schemas,
  durable record formats). Changing the contract is an architecture event:
  design-authority approval, version bump, breaking-change declaration, and
  where the repo's conventions require it, an ADR. Implementations on either
  side may churn freely.
- **Guarded** (package-level ports, internal APIs consumed by more than one
  component). Changes ride ordinary review, but the contract diff must be
  explicit (generated artifacts regenerate in the same PR) and consumers must
  be updated in the same change.
- **Fluid** (internals behind a boundary). No contract of their own beyond
  the language's type system; constrained only transitively, by the boundary
  above.

The lock level is data in the architecture registry, so tooling (and agents)
can read it, and raising or lowering it is itself a gated change.

Two rules borrowed from SPARK's assurance-ladder experience apply to both
lock levels and oracle composition: every level has a **machine-decidable
exit criterion** (a named set of checks that pass), never a judgment call;
and transitional states are named as transitional - "structural and syntactic
layers only" is a starting point for a locked boundary, not a destination,
because parking there forever while calling the boundary contracted is
verification theater.

## The oracle battery

The battery is what converts a contract from documentation into enforcement.
Composition depends on the boundary's risk score; the full menu:

1. **Conformance suite.** The port's behavior specified once as an abstract
   test suite; every implementation (the in-memory test double and the real
   adapter) must pass it identically. Kills the unverified-mock problem and
   makes implementations provably swappable.
2. **Property tests.** Declared invariants that are generative (round-trips,
   idempotency, ordering, state-machine closure) get property-based tests
   (jqwik, fast-check, Hypothesis) rather than examples.
3. **Negative suites.** Generated from contract data: every authenticated
   endpoint class gets anonymous-denied / wrong-role-denied /
   cross-scope-denied; every input schema gets malformed-input rejection;
   every protocol gets illegal-transition rejection.
4. **Golden and replay corpora.** Parsers, renderers, and transformers carry
   input-to-exact-output pairs; detection-style logic carries positive and
   negative datasets with pinned match counts.
5. **Executable reference model and differential testing.** For the highest
   value boundaries, the design authority ships a slow but obviously right
   reference implementation; the real implementation must agree with it on
   generated inputs, with divergences minimized into counterexamples. This is
   the strongest practical oracle for a weak implementer, and the core trick
   of Amazon's lightweight-formal-methods work (ShardStore): the spec is
   executable, so agreement is checkable, cheaply and forever. ShardStore's
   companion trick is the anti-drift mechanism: the reference model doubles
   as the mock in unit tests, so changing behavior forces updating the spec -
   the coupling that kept classical Design-by-Contract assertions from
   rotting is built in rather than hoped for.
6. **Formal specs.** TLA+/Alloy (design level) or SMT-backed contracts
   (code level, OpenJML here) where the risk score demands it: concurrency,
   delivery semantics (exactly once, at most once), isolation, security-critical state machines.
   Every spec is wired to CI and carries an explicit map from spec actions to
   code paths; a spec nobody checks is a PDF.
7. **Mutation testing as the meta-oracle.** The battery's own strength is
   measured by seeding mechanical defects and requiring the battery to catch
   them, with per-boundary minimum scores. This is the specific control
   against the best-documented failure mode of agent-written verification:
   plausible, green, and vacuous tests.

Every declared invariant carries a stable ID, and an inventory maps each ID to
its enforcing check. Policy fails an invariant with no check, and fails a
change that removes a check without removing (or re-homing) the invariant.
The inventory is what makes "the contract and its tests stay stable while the
implementation churns" a checkable property rather than an aspiration.

One precondition underlies the entire battery: **deterministic CI**. A flaky
or environment-dependent gate is not a gate - an optimizer retries through
noise - so hermetic verification environments, flake quarantine, and
read-only golden files are part of the quality system itself, not
conveniences layered on later.

## The implementation sandbox

An implementation work item hands the implementer:

- the contract package (all layers, plus the invariant inventory),
- the oracle battery, already failing,
- module-local context (the code inside the boundary),
- and a definition of done: battery green, mutation score at threshold,
  architecture rules green, protected paths untouched.

Protected paths (contracts, battery, registry, policy checks) are enforced by
a CI gate: an implementation diff touching them without a design-authority
approval marker fails. Battery weakening (deleted tests, skips, lowered
thresholds) is the same violation through a different door and the gate
treats it identically.

Residual gaming channels exist and are handled honestly rather than assumed
away: an implementer can still special-case visible test inputs. The
mitigations are layered - property tests and differential testing generate
inputs the implementer has not seen; mutation scoring punishes suites that
only assert on fixtures; and the independent review pass carries an explicit
anti-gaming checklist. Where the stakes justify it, a held-out oracle subset
runs only in CI.

## The spec lifecycle

The method moves the residual risk into the contracts themselves, which is
the point: all scarce attention (human and strong-model) concentrates on a
small, stable surface. That surface gets its own quality machinery:

1. **Spec review is the review.** The design authority's output is reviewed
   the way code used to be: adversarially, for permissiveness ("what wrong
   implementation would this contract admit?"), for completeness (traceability
   to the requirements it discharges), and for testability (every clause maps
   to a battery element).
2. **Spec changes are first-class events.** Versioned schemas, a
   breaking-change gate with declared migrations, deprecation records, and
   regenerated consumers in the same change.
3. **Drift control.** Path coupling ties spec files to the code that
   implements them: a change to implementing code without a spec touch (or an
   explicit no-spec-impact declaration) is flagged. Periodic re-derivation
   (in Ground Control's vocabulary: the GC-GRC drift machinery) compares the
   recorded model against reality.
4. **Spec defect feedback.** Every defect that escapes to review or
   production is triaged as battery gap, spec gap, or spec error, and the
   fix lands in the contract layer, not just the code.

## What this method does not claim

- **Green does not mean proved.** Oracles are partial; total functional
  correctness is undecidable in general and unaffordable in particular. The
  claim is weaker and more useful: each independent oracle layer multiplies
  down the probability that a wrong implementation survives, and the residual
  is measured (mutation score, invariant coverage, seeded-defect catch rate)
  instead of felt.
- **Spec risk does not vanish; it concentrates.** A wrong contract yields a
  correctly implemented wrong system. The method's bet is that a small, typed,
  reviewed, versioned spec surface is a far better place to spend scarce
  senior attention than a large, churning implementation surface. seL4 and
  CompCert made the same bet at full strength; this is the lightweight
  version.
- **Not everything deserves the full stack.** Layer assignment is risk-scored
  (the existing ADR-012 L0-L3 ladder, extended per boundary). Over-contracting
  fluid interiors is a named anti-pattern: it slows everyone and dilutes
  attention on the boundaries that matter.
- **The method has a floor cost.** Contract-package authoring plus battery
  construction is real work, front-loaded. Amazon reported roughly 13% of
  code budget for executable reference models alone; the full stack lands
  higher on locked boundaries. It buys back: weak-tier implementation becomes
  viable, review shrinks to spec review, and regressions hit walls instead of
  users. The pilot (issue #1296) exists to measure this trade on real work
  rather than assert it.

## Fit to capability tiers

The method is designed for a world of stratified model capability:

- **Frontier tier** (plus the human): contract authoring, invariant
  discovery, reference models, spec review, defect triage. Low volume, high
  stakes, stable artifacts.
- **Mid tier:** battery scaffolding from contracts, interior test authoring,
  ordinary implementation of guarded boundaries.
- **Low tier:** implementation inside locked boundaries against a failing
  battery - the sandbox makes the tier safe to use.
- **Mechanical:** everything in CI, which is where all enforcement lives.

Predictability comes from the sandbox: the low-tier implementer's failure
modes are bounded to "does not go green" (visible, cheap, retryable) rather
than "goes green wrongly" (invisible, expensive), to the degree the battery's
measured strength supports - which is exactly the number the evaluation
harness (issue #1297) reports.

## References for load-bearing claims

- Separation of powers and statistical certification: Mills, Dyer, and
  Linger, "Cleanroom Software Engineering," IEEE Software 4(5), 1987,
  <https://doi.org/10.1109/MS.1987.231413>.
- Build quality into the process, not inspection: Deming, *Out of the
  Crisis*, MIT Press, 1986.
- Poka-yoke / mistake-proofing: Shingo, *Zero Quality Control: Source
  Inspection and the Poka-Yoke System*, Productivity Press, 1986.
- Reference models, conformance checking, models-as-mocks, and the 13-20%
  overhead figure: Bornholt et al., "Using Lightweight Formal Methods to
  Validate a Key-Value Storage Node in Amazon S3," SOSP 2021,
  <https://doi.org/10.1145/3477132.3483540>.
- Assurance-ladder rules (machine-decidable exit criteria; transitional
  levels are not destinations): AdaCore and Thales, *Implementation
  Guidance for the Adoption of SPARK*,
  <https://www.adacore.com/uploads/books/pdf/ePDF-ImplementationGuidanceSPARK.pdf>.
- Mutation testing as the measurement of test strength: DeMillo, Lipton,
  and Sayward, IEEE Computer 11(4), 1978,
  <https://doi.org/10.1109/C-M.1978.218136>; Petrovic and Ivankovic,
  ICSE-SEIP 2018, <https://doi.org/10.1145/3183519.3183521>.

Full per-tradition sourcing, including the LLM-era failure register, is in
[prior-art.md](prior-art.md).
