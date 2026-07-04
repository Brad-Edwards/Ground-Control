# Contract-Locked Development (CLD)

Research and method-definition docs for milestone 18 (issue #1290). Status:
reviewed research packet; the binding method authority is ADR-087, authored
under #1291.

## The method in one page

**Goal.** Demonstrable (not always provable) correctness, quality control,
and predictable outcomes for code written by automated systems - and by
extension anyone - by making it structurally hard to implement the wrong
thing against a boundary.

**Frame.** Ground Control's design intent: software development, and
eventually operations, as a manufacturing process. CLD is that process's
quality system, built to reduce error and rework: contracts are engineering
drawings with tolerances, lock levels are design freeze plus engineering
change orders, types are poka-yoke, oracle batteries are gauges and
fixtures, mutation testing is gauge calibration, and Ground Control is the
traveler. Two commitments follow: agents change the *price* of proven
controls, not the definition of good - so agent labor buys more formal
methods and conformance machinery, never more generation or more reviewers
voting; and once the process variables (first-pass yield, rework, escape
rate, gate cost) are measured, improving the pipeline becomes an
operations-research problem, not a matter of taste. The method's authority
is the classical canon (Cleanroom, correctness by construction, SPARK,
refinement, AWS lightweight formal methods, mutation testing); LLM-era
practice appears only as a failure-mode register - the adversary model the
controls contain.

**Core inversion.** The contract package for a boundary - not the
implementation - is the primary artifact. It is authored first and locked;
implementation is subordinate and replaceable, with one definition of done:
the contract's enforcement battery is green.

**Separated powers** (Cleanroom's lesson, mechanized):

- The **design authority** (strong model + human review) authors contracts,
  invariants, the oracle battery, and the architecture registry.
- The **implementer** (weaker model or human) writes code and interior tests,
  and cannot modify contracts, battery, registry, or policy - protected
  paths, enforced in CI, not by etiquette.
- The **verifier** is mechanical CI plus independent review, and its own
  strength is measured (mutation testing, seeded defects), because an
  unmeasured oracle rots.

**The contract stack**, per boundary: structural (architecture as code),
syntactic (schemas + generated consumers + drift/breaking gates), semantic
(invariants + conformance suites + property tests), protocol (state machines,
formal specs where risk scores demand), policy (authorization matrix,
redaction, confinement as data). Every boundary gets layers 1-2; risk scoring
decides the rest. Interior code stays free because the boundary above it is
strong.

**Lock levels**: Locked (module/service boundaries; contract change is a
gated architecture event), Guarded (ports; contract diffs explicit, consumers
regenerate in the same change), Fluid (internals; constrained only
transitively). Levels are data with machine-decidable exit criteria.

**Why it fits stratified model capability.** Expensive attention (frontier
model + human) concentrates on a small, stable, legible spec surface; cheap
implementation runs inside a sandbox whose failure mode is "not green"
(visible, retryable) rather than "green but wrong" (invisible) - to the
degree the battery's measured strength supports, which is a number the
evaluation harness reports rather than a feeling.

**Honest limits.** Oracles are partial; spec risk concentrates rather than
vanishes; the full stack has a real floor cost (Amazon reported ~13-20%
overhead for the reference-model pattern alone, against 3-10x for full
verification); not everything deserves it - risk scoring decides.

## Documents

| Doc | Content |
|-----|---------|
| [method.md](method.md) | The full method: powers, contract stack, lock levels, oracle battery, sandbox, spec lifecycle, limits, capability-tier fit |
| [prior-art.md](prior-art.md) | Cited synthesis of the classical canon (Cleanroom, DbC, refinement, SPARK, AWS lightweight formal methods, mutation testing), each reduced to transferable lessons, plus an LLM-era failure-mode register kept strictly as threat model |
| [mechanism-catalog.md](mechanism-catalog.md) | Concrete tooling per contract-stack layer for this portfolio's stacks, adopt-now vs adopt-later |
| [ground-control-integration.md](ground-control-integration.md) | Productization path (method → repo-native gates → pilot → workflow lanes → portfolio kit), pilot plan anchored to milestone 17's #1277, open questions for review |

## Milestone 18 map

| Issue | Work |
|-------|------|
| #1290 | These docs |
| #1291 | ADR-087 method authority + GC-CLD requirement wave |
| #1292 | Oracle battery toolkit |
| #1293 | Mutation-testing gate |
| #1294 | Spec-authority separation (protected paths) |
| #1295 | Architecture-as-code registry |
| #1296 | Pilot on Temporal activities (#1277) |
| #1297 | Evaluation harness |
| #1298 | Ground Control productization (/design lane) |
| #1299 | Portfolio packaging |
