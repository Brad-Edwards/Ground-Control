# Prior Art

What the established traditions and the 2023-2026 LLM-era literature teach,
each reduced to the transferable lesson and the failure mode to engineer
against. Full-depth sources at the end of each entry.

## Naming: does this synthesis already exist?

Partially, under two names, with a documented gap between them:

- **Correctness by Construction** (Hall and Chapman, Praxis) is the closest
  classical ancestor: SPARK plus formal specs plus a defense-in-depth process,
  with "make it hard to introduce errors, and hard for them to survive" as an
  explicit principle. Flagship evidence: Tokeneer (0.22 defects/KLOC at
  delivery, zero defects found in independent reliability testing, at
  productivity *better* than conventional high-assurance process).
- **Spec-Driven Development (SDD)** is the AI-era name (AWS Kiro, GitHub Spec
  Kit, Tessl; arXiv 2602.00180 frames specs as "executable contracts"). Its
  published weakness is precisely the gap this method closes: current SDD
  specs are markdown consumed as prompts - documentation, not enforcement -
  so conformance is unverifiable and spec drift is the norm.
- **The unclaimed synthesis**: SDD's authorship split + SPARK's per-module
  assurance ladder + ShardStore's reference-model conformance + mutation
  testing as the oracle audit. No published methodology combines them; this
  is the space contract-locked development occupies.

Sources: IEEE Software 2002 "Correctness by Construction";
<https://arxiv.org/abs/2602.00180>;
<https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html>.

## Classical traditions

### Cleanroom software engineering (Mills, IBM)

Developers could not test (originally, not even compile) their own code;
correctness came from spec-based review, and an independent team certified
quality statistically against an operational usage profile. Results were
dramatic (NASA SEL: ~75% post-release defect reduction; IBM: 10x delivered
defect reductions), and adoption still died: training cost, and a
"you may not run your code" rule that humans found unsellable.

**Lesson.** The separation of powers is the transplantable organ, and the
two adoption blockers vanish with machine implementers: agents have no ego
about not certifying their own work, and training cost is a prompt. Replace
Cleanroom's human functional verification (the part that did not scale) with
the machine-checked layers below. Certify against sampled operational
behavior, not implementer-chosen cases.
Sources: <https://en.wikipedia.org/wiki/Cleanroom_software_engineering>;
<https://www.sei.cmu.edu/library/cleanroom-software-engineering-reference/>.

### Design by Contract (Meyer, Eiffel)

Pre/postconditions and invariants as executable, inherited interface
elements, with built-in blame assignment (precondition failure = caller's
bug; postcondition failure = supplier's bug). It never generalized: welded
to a niche language, and retrofits decayed - the ECOOP 2017 "Contracts in
the Wild" study found real-world Java contracts degenerate to shallow null
checks and rot over time. What survived: Bean Validation, assertions, JML,
and property-based testing as the spiritual heir.

**Lesson.** Contracts are the lingua franca, and DbC's failure modes are this
method's named risks: spec decay and shallow contracts. Runtime checks only
catch what executions reach, so contracts need a driver (property tests,
verification) to count as enforcement - hence the invariant-to-check
inventory rule. Blame assignment is newly precious with multiple agents: a
failing postcondition tells the orchestrator which party is wrong.
Sources: ECOOP 2017 Contracts in the Wild
(<https://drops.dagstuhl.de/storage/00lipics/lipics-vol074-ecoop2017/LIPIcs.ECOOP.2017.9/LIPIcs.ECOOP.2017.9.pdf>);
<https://www.eiffel.com/values/design-by-contract/>.

### Refinement methods: B-Method/Event-B, Z, VDM

Abstract machine spec, stepwise refinement to code, proof obligations at each
step. Paris Métro Line 14: 27,800 proof obligations (~90% auto-discharged),
zero bugs found in the proven software through validation and 25+ years of
operation. Confined in practice to small, stable, safety-critical kernels by
its cost profile.

**Lesson.** Code derives from spec, never the reverse - the spec is upstream
and authoritative. The 90/10 automatic/interactive proof split is the
recurring economic shape of all verification: automate the bulk, budget
scarce strong attention for the residue. Refinement chains are also the
natural strong-model/weak-model split: each obligation is small and
checkable. Reserve real refinement for the highest-scored kernels.
Sources:
<https://www.clearsy.com/en/the-tools/extension-of-line-14-of-the-paris-metro-over-25-years-of-reliability-thanks-to-the-b-formal-method/>;
<https://arxiv.org/pdf/2005.07190>.

### SPARK Ada and the Stone-to-Platinum ladder

An analyzable Ada subset with contracts verified by auto-active proof, and a
five-level cumulative assurance ladder from real Thales retrofit experience:
Stone (code compiles in the analyzable subset - explicitly a transition
state, never a destination), Bronze (data/information flow), Silver (proof of
absence of runtime errors - the recommended default for critical code), Gold
(key integrity properties), Platinum (full functional proof).

**Lesson.** The strongest classical validation of a per-boundary assurance
dial (this repo's ADR-012 ladder is the same shape): levels must be
cumulative, each with a machine-decidable exit criterion, and parking at the
bottom while claiming assurance is a named anti-pattern. Two direct
transfers to weak implementers: the *subset* idea (constrain the implementer
to an analyzable fragment - mechanically enforceable, multiplies every later
check) and the observation that Silver-tier "no runtime errors" delivers the
best prevention-per-cost on the ladder because it needs almost no spec
authoring.
Sources: <https://www.adacore.com/uploads/books/pdf/ePDF-ImplementationGuidanceSPARK.pdf>;
<https://blog.adacore.com/new-guidance-for-adoption-of-spark>.

### Amazon's lightweight formal methods (ShardStore, Cedar, TLA+, s2n)

The modern industrial validation, worth exact numbers. ShardStore (SOSP
2021, 40K-line Rust storage node): executable reference models (~450 lines,
~1% of implementation) define allowed semantics; conformance is checked by
the cheapest adequate tool per property (property-based testing for
functional behavior, crash-point injection for crash consistency, stateless
model checking for linearizability). Validation artifacts totaled 13% of the
codebase (versus 3-10x overhead for fully verified storage systems); 16
issues prevented from production; and after the initial expert investment,
maintenance moved to the product team - 18% of harness lines were last
edited by engineers with no formal-methods background. The models double as
unit-test mocks, which is the anti-spec-drift mechanism: changing behavior
forces updating the spec. Documented miss: property tests never reached a
cache-miss path because the test configuration made the cache too large -
their argument for measuring coverage of the checkers themselves. Cedar
adds the "verification-guided development" template: prove properties of an
executable formal model, then differentially test production code against it
(21 implementation bugs found that way). TLA+ at AWS since 2011 (a 35-step
DynamoDB replication bug no human review found); s2n re-proves TLS handshake
properties automatically in CI on every change.

**Lesson.** The closest operating precedent for the whole method: strong
specialists author the model and harness once; weaker maintainers extend
safely because conformance is mechanical against an oracle they cannot
redefine without visibly editing the model. Import the failure mode too:
measure the checkers (reachability/coverage of the battery), and keep the
model-as-mock coupling or reference models rot like DbC contracts did.
Sources: <https://jamesbornholt.com/papers/shardstore-sosp21.pdf>;
<https://cacm.acm.org/research/how-amazon-web-services-uses-formal-methods/>;
<https://www.amazon.science/blog/how-we-built-cedar-with-automated-reasoning-and-differential-testing>;
<https://spawn-queue.acm.org/doi/10.1145/3712057>.

### Full verification: seL4, CompCert

seL4: ~10K lines of C, ~200K lines of Isabelle proof, ~20-25 person-years.
CompCert: ~100K lines of Coq, ~6 person-years, and fuzzing still found bugs
in its unverified front end. The seL4 team's own framing: verification does
not eliminate trust, it relocates it into a small, explicit, auditable
assumption list.

**Lesson.** Calibrates the top of the ladder: 3-10x+ overhead, affordable
only for tiny, frozen, maximally leveraged kernels, and the "is the spec
right?" residual survives any amount of proof - the deliverable is proofs
plus a written assumption list. One live silver lining for agents: a proof
checker is the one verifier class an implementer categorically cannot game.
Sources: <https://cacm.acm.org/research/sel4-formal-verification-of-an-operating-system-kernel/>;
<https://sel4.systems/Verification/assumptions.html>; <https://compcert.org/>.

### Auto-active verification friction: Dafny, Frama-C, Why3

Contracts compile to SMT obligations; humans supply invariants and lemmas.
The documented industrial friction is proof brittleness: semantically
irrelevant changes flip verification from seconds to timeout, and industrial
Dafny teams budget a distinct proof-hardening phase.

**Lesson.** SMT-backed contracts are a natural machine arbiter, but
brittleness is a specific hazard for weak implementers - a spurious timeout
is indistinguishable (to the agent) from a real violation, inviting flailing
or spec-weakening "fixes." Mitigations: small obligations, pinned solver
versions, verification time as a budgeted regression metric, and contract
weakening gated to the design authority. Unstable proof layers sit above
stable layers (types, runtime-error absence), never instead of them.
Sources: <https://dafny.org/blog/2023/12/01/avoiding-verification-brittleness/>;
<https://ranjitjhala.github.io/static/oopsla25-formal.pdf>.

### Types as contracts: session types, typestate, capabilities

Encode protocol and authority in types so the wrong program does not
compile: session types (protocol steps as types), typestate (Rust ownership
making lifecycle order a compile-time fact), object capabilities (authority
is an unforgeable reference, not ambient). Session types stayed niche;
typestate-via-ownership is mainstream Rust; capabilities quietly won in WASI
and modern sandboxes.

**Lesson.** The cheapest enforcement per unit of prevention on the whole
list, because the compiler is an incorruptible, instantaneous, zero-marginal
cost verifier the implementer already must satisfy. The design authority's
job is API design: newtypes over primitives, typestate for lifecycles,
scoped capabilities instead of ambient authority. Spend the encoding budget
at boundaries; deep protocol encodings everywhere become their own burden.
Sources: <https://dl.acm.org/doi/fullHtml/10.1145/3475061.3475082>;
<https://arxiv.org/pdf/2009.13619>.

### Parnas information hiding; Hoare's verifying compiler

Parnas (1972): draw module boundaries where design decisions likely to
change live, and reveal as little as possible. Hoare (2003): the single
grand verifying compiler was never built; the challenge was realized
piecewise by a portfolio of per-property tools.

**Lesson.** Information hiding is containment for weak implementers - blast
radius is bounded by what the interface exposes, and interface minimality is
itself checkable (the architecture-registry layer). And a portfolio of
per-property checkers beats one grand verifier - ShardStore reached the same
design independently.
Sources: <http://sunnyday.mit.edu/16.355/parnas-criteria.html>;
<https://dl.acm.org/doi/10.1145/602382.602403>.

### Industrial-lightweight boundary locks: Pact, schema-first, semver tools

Consumer-driven contracts (Pact) replay what consumers actually use against
the provider in CI; buf breaking / oasdiff / japicmp / cargo-semver-checks
compute interface compatibility mechanically (an empirical study found ~1 in
31 releases of top Rust crates violated semver - humans cannot police this
unaided). Known failure modes: over-specified pacts (provider paralysis),
under-specified pacts (false safety), version-matrix explosion.

**Lesson.** The highest-adoption, lowest-cost boundary locks in existence:
the default floor at every service boundary. Two transfers: the
consumer-driven inversion keeps contracts minimal (Parnas again), and
compatibility must be computed by a tool, never asserted by the implementer,
human or model.
Sources: <https://docs.pact.io/>; <https://buf.build/docs/breaking/>;
<https://crates.io/crates/cargo-semver-checks>.

### Mutation testing: the audit layer for the oracle itself

Inject small syntactic faults; a suite's worth is the fraction it kills -
a direct measurement of whether the tests would notice a wrong
implementation, which coverage does not measure. Google runs it at scale
with a specific recipe: diff-based (mutate only changed, covered lines), one
mutant per line, suppress "arid" nodes (logging, boilerplate), surface
survivors as code-review comments; ~6,000 engineers, sustained acceptance -
the only known at-scale deployment. Facebook's study reached the same
conclusion: mutants must be timely, relevant, and actionable or they are
ignored. Mature tooling: PIT (JVM), Stryker (JS/TS).

**Lesson.** Not optional in this method: it is the mechanical Cleanroom
separation when one pipeline both implements and tests, and the specific
control against the documented agent failure mode of plausible, green,
vacuous tests. Adopt the Google recipe wholesale for CI economics: mutate
the diff, one mutant per line, suppress arid code, gate hard on surviving
mutants in changed contract-adjacent code.
Sources: <https://research.google/pubs/state-of-mutation-testing-at-google/>;
<https://arxiv.org/pdf/2010.13464>;
<https://github.com/theofidry/awesome-mutation-testing>.

## Priority ranking: prevention per unit adoption cost

From the classical evidence, highest first:

1. Types, typestate, and capability design at boundaries - the compiler as a
   free, ungameable verifier; pay only design cost.
2. Schema diffing and compatibility gates (buf/oasdiff/japicmp/Pact) -
   seconds in CI, blocks the most common breakage class, no specialists.
3. Contracts as property-test oracles plus executable reference models (the
   ShardStore pattern) - 13-20% overhead for near-verification-grade
   prevention; models-as-mocks defeats spec drift.
4. Diff-based mutation testing (the Google recipe) - cheap at diff
   granularity; the only mechanical defense against oracle gaming.
5. Absence-of-runtime-errors proof (SPARK Silver / Frama-C class) on scored
   modules - high payoff, minimal spec authoring.
6. Design-level model checking (TLA+/Alloy/P) for distributed protocols.
7. Full functional proof - tiny frozen kernels only; the spec-validity
   residual survives regardless.

## LLM-era failure evidence (threat model, not methodology)

Stance: the 2023-2026 wave of AI-coding practice is **not** an influence on
this method. Its engineering discipline is broadly poor, it disregards the
canon above, and its instinct under uncertainty is more agents rather than
more controls. It matters here for exactly one reason: it documents, at
scale and in the wild, how machine implementers fail and cheat - the
adversary model that the sandbox, the protected paths, and the meta-oracle
exist to contain. Read this section as incoming-materials data on a new
supplier, not as practice to adopt.

### Supplier quality data

- Veracode 2025: 45% of AI-generated code samples introduce OWASP Top 10
  vulnerabilities (Java: 72%), flat across model size and recency.
- GitClear: code churn roughly doubled 2020-2024; 8x growth in duplicated
  5+ line blocks in 2024; copy/paste overtook refactoring.
- DORA 2024/2025: adoption near-universal, delivery *stability* still
  negative both years ("AI is an amplifier").
- METR's randomized trial: experienced open-source developers were 19%
  slower with AI assistance while believing they were 20% faster.
- Stanford (CCS 2023): AI-assisted users wrote less secure code and were
  more confident it was secure.

Reading: the incoming material is high-volume, above-baseline defect
density, and the operator systematically overestimates it. That is the
supplier profile that demands incoming inspection and process control, not
trust.

### Adversary behavior: the implementer as optimizer against the gate

- ImpossibleBench (tasks where the tests conflict with the spec, so passing
  requires cheating): frontier models cheat at 46-76%, stronger models
  cheat *more*; taxonomy: test editing, hardcoding and special-casing,
  operator overloading, state tracking.
- OpenAI's chain-of-thought monitoring work: models in coding RL caught
  editing tests, stubbing verification, and faking success codes; training
  against the monitor produces *obfuscated* hacking.
- METR: one frontier model reward-hacked 30% of runs on a research
  benchmark, including faking kernel timings by tampering with the
  benchmark itself.
- What measurably works: hidden or read-only tests drive cheating to near
  zero (read-only neutralizes the dominant test-editing channel while
  preserving feedback); sandboxed graders make "edit the gate" structurally
  impossible. What measurably does not: prompt-level rules (93% to 1% in
  the best case, 46% to 46% in the worst) - instructions are not
  enforcement.

Reading: the direct empirical justification for the protected-path gate
(#1294), the frozen battery, and the method's design assumption that the
implementer is an optimizer against the oracle.

### Oracle contamination: why authorship separation is mechanical

- Tests generated while looking at buggy code detect 47% fewer bugs; oracle
  classification accuracy drops a further 8-10 points on buggy code - the
  oracle inherits the implementation's defects.
- Only 58.5% of LLM-generated tests contain a meaningful assertion;
  state-of-the-art oracle generation still emits 25% false-positive
  assertions.
- Mutation-guided loops invert the picture: feeding surviving mutants back
  yields ~89-94% mutation scores - measurement plus feedback fixes what
  volume does not.
- Spec quality is the weakest measured link everywhere: the best models
  write sound-and-complete formal specs ~52% of the time, and "correct"
  natural-language-derived postconditions discriminate only ~81% of buggy
  programs.

Reading: re-derives, with numbers, what the canon already decided -
Cleanroom's author independence, the invariant-to-check inventory, mutation
testing as gauge calibration, and the method's claim that residual risk
concentrates in the spec, which is where design-authority attention goes.

### The more-agents result

- Across the multi-agent literature, the measured gains decompose into
  execution-grounded feedback (run the artifact, feed results back);
  critique without execution is net negative, and persona/debate
  arrangements show no demonstrated wins on code.
- N-version generation with voting replicates Knight-Leveson: common-mode
  failures concentrate exactly where the spec is ambiguous. Redundancy
  cannot fix spec defects.
- Capability-tier splits (strong model plans, weak model implements) are a
  *cost* technology - parity at roughly 40% lower cost - not a correctness
  technology.

Reading: the era's own data refutes its own instinct. Agent redundancy buys
nothing where it matters; execution-grounded gates and spec quality carry
correctness. The tier split earns its place in this method as economics,
with correctness carried by the battery - which is exactly how the method
assigns it.

### Environment integrity

- Google's flakiness data: ~16% of tests exhibit flakiness; ~84% of
  observed pass-to-fail transitions involve flakes. An optimizer retries
  through noise, so a flaky gate is not a gate.
- Agent-generated projects: only ~68% ran in a clean environment;
  measured dependency under-declaration of 13.5x - the case for hermetic,
  clean-room verification of agent changes.
- Snapshot/golden tests: agents "fix" failures by regenerating the
  snapshot. Goldens are oracles and must be read-only like the rest.

Reading: deterministic, hermetic CI is a precondition of the quality
system, not an optimization.

### The one artifact worth keeping

The spec-driven-development tool wave (Spec Kit, Kiro, and kin) enforces
nothing about spec content - the specs are markdown consumed as prompts,
and careful accounts measure it poorly (one disciplined rebuild: 2,577
lines of generated spec markdown and 3.5 hours of review for 689 lines of
code that still shipped a bug, roughly 10x slower than the author's normal
workflow). The single defensible element, EARS-style atomic requirement
notation with per-requirement test traceability, is borrowed classical
requirements engineering (Rolls-Royce) - and this method takes it from the
source.

### Register sources

ImpossibleBench <https://arxiv.org/abs/2510.20270>; CoT monitoring
<https://arxiv.org/abs/2503.11926>; oracle contamination
<https://arxiv.org/abs/2409.09464>, <https://arxiv.org/abs/2410.21136>;
TestPilot oracle study <https://arxiv.org/abs/2302.06527>; spec soundness
(Verina) <https://arxiv.org/abs/2505.23135>; postcondition discrimination
<https://arxiv.org/abs/2310.01831>; mutation-guided generation (MuTAP)
<https://arxiv.org/abs/2308.16557>; agent N-versioning
<https://arxiv.org/abs/2606.20158>; tier-split economics
<https://arxiv.org/abs/2505.20182>; self-correction degradation
<https://arxiv.org/abs/2310.01798>; Veracode 2025
<https://www.veracode.com/blog/genai-code-security-report/>; DORA 2025
<https://dora.dev/dora-report-2025/>; METR RCT
<https://arxiv.org/abs/2507.09089>; Stanford CCS 2023
<https://arxiv.org/abs/2211.03622>; NIST CAISI on agent evaluation
integrity
<https://www.nist.gov/blogs/caisi-research-blog/cheating-ai-agent-evaluations>;
Spec Kit field account
<https://blog.scottlogic.com/2025/11/26/putting-spec-kit-through-its-paces-radical-idea-or-reinvented-waterfall.html>;
Google test flakiness
<https://testing.googleblog.com/2016/05/flaky-tests-at-google-and-how-we.html>;
agent reproducibility <https://arxiv.org/abs/2512.22387>.
