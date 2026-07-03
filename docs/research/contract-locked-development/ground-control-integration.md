# Ground Control Integration and Pilot Plan

How contract-locked development (CLD) becomes Ground Control machinery: first
a discipline this repo runs on itself, then a workflow product any
Ground-Control-aware repo inherits. Staging is deliberate: nothing below the
docs line executes until the method ADR (#1291) is accepted.

## Why Ground Control is the natural host

Ground Control already owns every substrate the method needs:

- **Requirements and traceability graph** (ADR-011): contracts are artifacts;
  `SPEC`, `PROOF`, and `TEST` artifact types already exist. A requirement can
  trace to the contract that discharges it, the contract to the checks that
  enforce it, and coverage questions become graph queries instead of audits.
- **Assurance ladder** (ADR-012): CLD does not replace L0-L3; it
  operationalizes when the ladder applies, per boundary instead of per class,
  and adds the two things the ladder does not have - separation of powers and
  a meta-oracle over test strength.
- **Gate machinery** (GC-O007/ADR-029/ADR-036): durable records, server-side
  assertions, per-step routing by capability tier. CLD's design-authority
  approval is one more durable marker family; its completion additions are
  more assertions in the same style.
- **Contract surface** (ADR-082, milestone 17): the first instance of the
  syntactic layer plus drift/breaking gates, being built for the Temporal
  program regardless. CLD generalizes what ADR-082 instantiates.
- **Derivation and drift machinery** (ADR-058 family): the GRC program
  already computes model-vs-code drift; the architecture registry is the same
  shape of fact for a different concern and should project into the same
  graph rather than fork a parallel model.

## Productization path

### Stage 0: Method (issues #1290, #1291)

The docs in this folder, the user review round, then the binding ADR plus a
requirement wave. Everything else is gated behind stage 0.

### Stage 1: Enforcement primitives in this repo (#1292-#1295)

The four mechanisms that make the method real, built repo-native the way the
changelog-fragment and enum-contract gates were:

- oracle battery scaffolds (#1292),
- mutation gate with per-boundary thresholds (#1293),
- protected-path / role-split gate (#1294),
- architecture registry as data (#1295).

Each lands as ordinary policy/CI machinery with its own tests; none changes
the /implement contract.

### Stage 2: Pilot on real work (#1296, #1297)

Measured trial before any workflow change. Substrate: milestone 17 phase 2
(#1277), because deterministic Temporal activities are the ideal pilot unit -
typed I/O contracts are already mandated there (ADR-028/ADR-082), scope is
small and crisp, and correctness is decidable by battery.

Pilot shape per activity:

1. Design authority (high-tier model, user reviews the contract package)
   authors: I/O schemas, invariants with IDs, conformance suite, property
   tests, negative suite, and where cheap, a reference model.
2. Battery is committed failing; protected paths locked.
3. A low-tier routed implementer (ADR-036 machinery) implements to green.
4. Measure: first-pass green rate, iterations to green, seeded-defect catch
   rate (plant N realistic defects in a variant run; count catches), cost
   versus the ordinary lane, and any defect the battery missed but review or
   CI caught.

The evaluation harness (#1297) turns those measurements into standing
numbers: mutation score per boundary, invariant coverage, seeded-defect catch
rate, defect escape rate. Exit is an explicit go/adjust/stop recommendation.

### Stage 3: Workflow productization (#1298)

Only after the pilot reports. Two workflow-visible changes:

- **A /design lane.** A workflow whose deliverable is a contract package:
  contracts, invariant inventory, oracle battery, registry updates. Routed
  high-tier, gated on design-authority approval recorded as a durable marker.
  Ships as a skill first; becomes a Temporal workflow type under ADR-081's
  program when the engine can carry it.
- **/implement consumes contract packages.** A work item can reference a
  contract package; the completion gate then additionally asserts battery
  green, mutation threshold met, and protected paths untouched. No GC-O007
  gate is weakened; CLD only adds assertions, in the existing
  `gc_assert_completion` style.

Graph integration rides along: contract packages and invariants become
traceable artifacts, giving the requirement-to-contract-to-check chain.

### Stage 4: Portfolio packaging (#1299)

The ADR-027 move: package the registry template, protected-path check,
mutation-gate config, scaffolds, and `.ground-control.yaml` keys as a
versioned kit a consumer repo adopts with configuration plus its own contract
content. Validated by onboarding one non-Ground-Control repo. This is what
turns "very strong interfaces all the way down" from a property of this repo
into a property of the portfolio.

## Relationship to milestone 17

Milestone 17 and this milestone are complementary and deliberately ordered:

- ADR-082 (milestone 17) builds the contract surface this method's syntactic
  layer needs; CLD does not duplicate it.
- The Temporal program's phase discipline (contracts before activities,
  ADR-081 locked decision 3) is CLD's phase-0 rule applied avant la lettre;
  the pilot runs inside it rather than beside it.
- Identity (GC-P024) eventually gives the design-authority role a real
  principal model; until then the approval marker plus CODEOWNERS carries it.

## The quality-system end state

CLD is the quality system of the manufacturing process Ground Control is
built to run, and its purpose in process terms is reducing error and rework.
The staged build-out above instruments the line: per-boundary first-pass
yield, rework loops, gate costs, and escape rates (#1297) join the run
economics and platform telemetry milestone 17 already ships (ADR-061,
GC-P025). The intended end state is that improving the pipeline stops being
taste and becomes operations research: gate placement, reference-model
budget, and mutation thresholds as inspection-allocation problems over
measured process variables, with rework and escape cost as the objective.
That formulation is out of scope for this milestone beyond one obligation:
the evaluation harness collects the variables the formulation needs, so the
optimization question is answerable later without re-instrumenting.

## Open questions for the review round

1. **Name.** "Contract-locked development" is proposed here; alternatives in
   the literature (spec-driven development, correctness by construction)
   carry adjacent but not identical meanings - see prior-art.md.
2. **How hard to lock interior tests.** The method leaves implementer-authored
   interior tests unprotected (they are implementation). Mutation scoring is
   the check on their strength. Is that enough, or should boundary-adjacent
   interior suites also be design-authority property?
3. **Held-out oracles.** Running a battery subset only in CI (invisible to the
   implementer) strengthens anti-gaming but complicates local
   reproducibility. Default off, per-boundary opt-in is the proposal.
4. **Reference-model budget.** Differential testing is the strongest oracle
   but the costliest authoring step. Proposal: mandatory only for
   data-integrity and security-critical locked boundaries.
5. **Requirement wave shape.** One requirement per enforceable property
   (registry, protected paths, battery composition, meta-oracle, lanes) or
   one umbrella plus per-mechanism children.
