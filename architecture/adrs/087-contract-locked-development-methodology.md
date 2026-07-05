# ADR-087: Contract-Locked Development Methodology

## Status

Accepted

## Date

2026-07-04

## Context

The Contract-Locked Development (CLD) research packet under
`docs/research/contract-locked-development/` defines a method for building
software with machine-checked contracts, separated authorship, and measured
verification strength. That packet is research material. It does not, by
itself, bind implementation issues or create traceability anchors.

Ground Control already has the substrate CLD needs:

- ADR-012 defines the L0-L3 assurance ladder.
- ADR-021, ADR-029, and ADR-036 define the gated `/implement` workflow,
  issue-thread durable records, model routing, and review records.
- ADR-058 defines the program-ADR pattern for a multi-issue governance wave
  with derived facts, mechanical gates, and no prompt-only enforcement.
- ADR-081 uses the same program-ADR pattern for the Temporal workflow and
  console program.
- ADR-082 creates the first concrete contract surface: committed contracts,
  drift gates, breaking-change gates, conformance suites, and invariant
  enforcement.
- ADR-084 makes contract and invariant vocabulary part of the context graph's
  concept-authority model.

Without this ADR, later CLD issues would have two failure modes. First, they
could implement disconnected mechanisms without one method-level authority.
Second, they could create prose-only rules that agents are asked to follow but
that CI, policy, or Ground Control cannot enforce.

## Decision

Adopt Contract-Locked Development as Ground Control's method for high-assurance
agentic implementation work. CLD is a quality system for the software factory:
the contract package for a boundary is the primary artifact, implementation is
subordinate and replaceable, and correctness claims are backed by named,
machine-checked evidence whose own strength is measured.

This ADR establishes method authority and the requirement wave. It does not
implement the follow-on machinery. Issues #1292 through #1299 build the
mechanisms in stages.

### 1. Three Powers Are Separated

CLD work separates three powers, and tooling enforces the separation:

| Power | Owns | Forbidden without authority |
|-------|------|-----------------------------|
| Design authority | Contract packages, invariants, oracle batteries, architecture registry entries, lock levels, and risk scoring | None, but changes are design events with versioning and approval records |
| Implementer | Implementation code and interior tests inside an assigned boundary | Contracts, oracle batteries, protected registry/policy paths, and battery-strength thresholds |
| Verifier | CI verdicts, review findings, mutation scores, seeded-defect scores, traceability assertions, and workflow records | The artifact being verified |

The design authority may be a high-tier agent plus human review until identity
and role data are strong enough to model it as a first-class principal. The
implementer may be a lower-tier model or a human. The verifier is mechanical
CI plus independent review tools. A verifier that only writes prose is not a
CLD verifier.

### 2. Contract Packages Are Layered

Every CLD boundary has a contract package with up to five layers:

| Layer | Contract content | Typical enforcement |
|-------|------------------|---------------------|
| Structural | Boundary identity, allowed dependencies, owners, lock level, risk score | Architecture registry checks, ArchUnit, depguard/eslint-boundaries |
| Syntactic | API, schema, IDL, payload, generated consumer shape | Regenerate-and-diff drift gates, breaking-change gates |
| Semantic | Invariants, preconditions, postconditions, error taxonomy | Conformance suites, property tests, invariant inventory |
| Protocol | Legal orderings, state machines, concurrency rules, lifecycle rules | Transition-matrix tests, replay tests, TLA+/Alloy/model checking where scored |
| Policy | Authorization, redaction, resource budgets, confinement rules | Policy-as-code checks and negative suites |

A layer counts only when it has a named machine check. Documentation with no
check is context, not an enforced contract layer.

### 3. Lock Levels Drive Change Protocol

Each registry boundary declares one lock level:

- **Locked:** service/module boundaries, published schemas, durable record
  formats, and contract packages. Contract change is a design-authority event
  requiring versioning, explicit compatibility or breaking-change handling, and
  the repo's declared approval record.
- **Guarded:** package ports and internal APIs consumed by more than one
  component. Contract diffs are explicit, generated consumers regenerate in the
  same change, and affected consumers update in the same change unless the
  design authority records an approved migration path.
- **Fluid:** internals behind a boundary. They carry no direct CLD contract
  beyond language, test, and transitive boundary obligations.

Lock levels are data in the architecture registry. A prompt, ADR paragraph, or
review comment that names a lock level without registry data is not enforceable
CLD state.

### 4. Oracle Batteries Define Done

A CLD implementation work item is done only when the oracle battery for its
boundary is green and the battery's measured strength meets the configured
threshold. Batteries are composed by risk score and may include:

- port conformance suites shared by every implementation of the port;
- property tests for generative invariants;
- negative suites generated from schemas, authz matrices, and protocol data;
- golden and replay corpora;
- executable reference models with differential tests;
- design-level formal specs where the risk score warrants them;
- mutation testing as the meta-oracle over battery strength;
- seeded-defect or held-out-oracle checks where the pilot justifies the cost.

Every invariant gets a stable identifier and an inventory row naming its
enforcing check. Removing or weakening a check without removing or re-homing
the invariant is a policy failure.

Issue #1292 adds the reusable oracle battery toolkit and selection guide for
these checks. The guide lives at
`docs/research/contract-locked-development/oracle-battery-toolkit.md`; it is
the operating reference for choosing conformance suites, property tests,
negative suites, golden/replay corpora, differential reference models, and
formal specs for a boundary.

### 5. Implementation Runs Inside a Sandbox

An implementation item gives the implementer:

- the contract package;
- the oracle battery, already failing for the new behavior;
- module-local implementation context;
- a definition of done: battery green, mutation threshold met where configured,
  architecture and policy gates green, and protected paths untouched.

Protected paths include contract packages, oracle batteries, architecture
registry data, policy checks, and battery-strength thresholds. Touching those
paths from the implementation lane without a design-authority approval marker
is a gate failure. Battery weakening, skipped checks, snapshot regeneration
that changes an oracle, or lowered thresholds are the same violation in
different form.

### 6. Specs Have a Lifecycle

Contract packages are versioned artifacts. Spec changes are reviewed as design
changes, not as implementation cleanup. A contract change records:

- the affected boundary and lock level;
- compatibility or breaking-change status;
- migration and regenerated consumer status when applicable;
- invariant inventory updates;
- oracle battery changes;
- traceability to requirements and ADRs.

Escaped defects are triaged as battery gap, spec gap, spec error, or
implementation error. Battery gaps and spec gaps are fixed in the contract
package or oracle battery, not only in the implementation that escaped.

### 7. Risk Scoring Selects Assurance

CLD does not require the full stack everywhere. Each boundary's risk score
selects the contract layers, lock level, and battery composition. The scoring
model starts with ADR-012's assurance ladder and extends it per boundary:

- low-risk fluid interiors usually need language types plus local tests;
- guarded boundaries need explicit structural and syntactic contracts, plus
  semantic checks for declared invariants;
- locked or high-risk boundaries need the relevant semantic, protocol, policy,
  and meta-oracle checks.

The method forbids blanket ceremony: over-contracting fluid internals is an
anti-pattern because it spends attention away from boundaries where escape cost
is high.

### 8. Relationship to Existing ADRs

CLD does not replace ADR-012. It operationalizes when and how ADR-012's L0-L3
assurance ladder is applied per boundary and adds separated powers plus
meta-oracle measurement.

CLD does not replace ADR-082. ADR-082 is the first reference implementation of
CLD's syntactic contract layer for Ground Control's REST, MCP, workflow, and
durable-record surfaces.

CLD does not replace ADR-058. ADR-058 owns derivation-backed GRC and drift
machinery. CLD uses the same program-ADR pattern and may consume the same
derived boundary facts when the architecture registry lands.

CLD does not change ADR-029's one-human-touchpoint contract. Future CLD
workflow lanes add design-authority records and battery assertions, but they
must not reintroduce synchronous plan approval into `/implement`.

CLD aligns with ADR-084 by registering contract, invariant, boundary, and
registry vocabulary as concept-authority content rather than inventing
unbound graph terms.

## Requirement Wave

Wave 9 is the CLD wave. The requirements are created in Ground Control as
DRAFT records until their implementation issues ship.

| Requirement | Purpose | Issue |
|-------------|---------|-------|
| GC-CLD-1 | Method authority and locked definitions | #1291 |
| GC-CLD-2 | Architecture registry and lock-level boundary model | #1295 |
| GC-CLD-3 | Oracle battery and invariant inventory | #1292 |
| GC-CLD-4 | Mutation meta-oracle gate | #1293 |
| GC-CLD-5 | Protected-path power separation | #1294 |
| GC-CLD-6 | Pilot on contracted Temporal activities | #1296 |
| GC-CLD-7 | Evaluation harness and process metrics | #1297 |
| GC-CLD-8 | Workflow productization | #1298 |
| GC-CLD-9 | Portfolio packaging kit | #1299 |

The issue links are DOCUMENTS links while the requirements remain DRAFT. Later
implementation PRs transition only the materially delivered requirements to
ACTIVE and reconcile IMPLEMENTS/TESTS links after merge.

## Build Order

1. **Stage 0, method:** this ADR and the wave above (#1291).
2. **Stage 1, enforcement primitives:** oracle battery scaffolds (#1292),
   mutation gate (#1293), protected paths (#1294), and architecture registry
   (#1295).
3. **Stage 2, pilot and measurement:** pilot CLD on Temporal activities
   (#1296) and measure the result (#1297).
4. **Stage 3, workflow productization:** add a design-authority lane and make
   implementation consume contract packages (#1298).
5. **Stage 4, portfolio kit:** package the method and machinery for
   Ground-Control-aware repositories (#1299).

No later stage may weaken an existing GC-O007, ADR-029, ADR-036, ADR-058, or
ADR-082 gate. CLD adds checks and authority records; it does not remove the
current workflow's traceability, review, CI, SonarCloud, or merge gates.

## Amendments

**2026-07-04 (issue #1293, mutation meta-oracle gate).** Stage 1 now includes
a repo-native mutation gate for the first registered CLD boundaries. Boundary
thresholds and baselines live in committed architecture-registry data under
`architecture/registry/`, not in Gradle, npm, Makefile, or CI YAML. The
`tools/mutation/run_boundary_mutation.py` runner maps PR changed paths to
registry selectors and invokes PIT or Stryker with fixed argv only for changed
mutation-contract boundaries. Interior-only changes emit a deterministic green
no-op. The CI `mutation` job is a required PR context on `main` and `dev`, and
`tools/policy/checks.py` validates the registry, runner, CI invocation, report
artifact upload, and branch-protection baseline remain synchronized.

## Consequences

### Positive

- Later CLD work has one binding authority and a requirement wave.
- Contract packages, oracle batteries, lock levels, protected paths, and
  mutation thresholds are defined as enforceable artifacts, not prompt rules.
- The method gives low-tier implementation a safe role: code can churn inside
  a boundary while the boundary's contract and battery stay stable.
- Existing Ground Control decisions remain coherent: ADR-012 supplies the
  assurance ladder, ADR-082 supplies the first contract surface, ADR-058
  supplies the program-ADR and drift pattern, and ADR-081 supplies a pilot
  substrate.

### Negative

- CLD adds front-loaded work to author contracts and batteries.
- The architecture registry and protected-path gates must become reliable
  before low-tier implementation can safely consume the method.
- Mutation and seeded-defect gates can add CI cost; #1297 must measure whether
  the reduced rework and escape rate justify that cost.

### Risks

| Risk | Mitigation |
|------|------------|
| The method becomes ceremony without machinery | This ADR only authorizes the method; #1292-#1295 must implement mechanical gates before productized CLD is claimed. |
| Teams over-contract low-risk internals | Risk scoring and the locked/guarded/fluid model make layer selection explicit and reviewable. |
| Implementers weaken batteries to pass | Protected-path and battery-weakening gates are first-class CLD requirements. |
| Specs are wrong | CLD concentrates scarce review on the spec surface and feeds escaped defects back into contract or battery fixes. |
| Workflow productization reintroduces extra human gates | ADR-029 remains binding; CLD records design authority but preserves PR merge as the one synchronous human touchpoint. |

## Non-Goals

- Implementing the oracle toolkit, mutation gate, protected-path policy,
  architecture registry, pilot, evaluation harness, workflow lane, or
  portfolio kit in this ADR.
- Replacing ADR-012, ADR-058, ADR-081, ADR-082, or ADR-084.
- Mandating formal specs or reference models for every class, function, or
  fluid internal module.
- Creating a second workflow configuration schema outside `.ground-control.yaml`
  and the future architecture registry.

## Related Requirements

- GC-CLD-1 through GC-CLD-9
- GC-O007 Gated Agentic Development Loop
- GC-O014 Contract-First Development Surface
- GC-O009 Workflow Orchestration via Temporal

## Related ADRs

- ADR-012 Formal Methods Development Process
- ADR-021 Gated Agentic Development Loop
- ADR-029 Issue-Thread Gate Model
- ADR-036 Per-Step Model Routing, Durable-Record Tool Surfaces, and Step
  Telemetry
- ADR-058 Derivation-First Continuous GRC
- ADR-081 Temporal Dev Workflow and Console Program
- ADR-082 Contract Surface Architecture and Enforcement Gates
- ADR-084 Context-Graph Concept Authority and Time Semantics
