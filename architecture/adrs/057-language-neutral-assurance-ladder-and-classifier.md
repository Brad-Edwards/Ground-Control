# ADR-057: Language-neutral assurance ladder and classifier

## Status

accepted

## Date

2026-06-06

## Context

ADR-012 defines the Ground Control assurance levels, but it binds the current
process to the Java Modeling Language (JML), OpenJML, and jqwik. The
`/implement` workflow also runs against Python, TypeScript, JavaScript, Java,
and documentation or infrastructure repositories. A Java-only assurance rule
does not cover those repositories, and it does not preserve ADR-027's
agent-neutral workflow boundary.

The existing pre-alpha default also leaves important surfaces to prose
classification. Security boundaries, state machines, directed acyclic graph
(DAG) operations, and corruption-prone domain mutators need an explicit
contract and a test before the completion gate accepts the change. Trivial data
transfer objects (DTOs), records, configuration, pure tag enums, and generated
schema mirrors must stay out of that requirement so the workflow does not add
ceremony where no behavior exists.

OpenJML Extended Static Checking (ESC) currently reaches only a narrow Java
surface: pure enum state packages without framework constructors or `String`
constructor false positives. Outside that scope, L1 contracts still provide a
valuable oracle for tests and reviewers, but they are test-backed and
documentation-grade rather than machine-proven.

## Decision

The assurance ladder is language-neutral. Each rung names a required assurance
property; each repository binds the property to concrete tooling through its
gate pack.

| Level | Name | Required property |
|-------|------|-------------------|
| L0 | Tested | Working implementation plus tests for significant behavior. |
| L1 | Contracted | Explicit preconditions, postconditions, invariants, or boundary validation, plus at least one test for each contract surface. |
| L2 | Property-Verified | L1 plus property-based tests or equivalent state-space checks for state machines, DAGs, parsers, codecs, and invariant-heavy logic. |
| L3 | Proven | Machine-checked proof for the bounded surface the prover can verify. |

The default floor is **scoped L1**. The workflow requires L1 on non-trivial
public boundary methods and classifier-detected critical surfaces. The floor is
not literal L1 on every public method. Accessors, DTOs, records, configuration
objects, pure tag enums, and generated mirrors are excluded unless they contain
behavior, transitions, or cross-field constraints.

Each language binding supplies the strongest practical provider for the rung:

| Capability | Java binding | Python binding | TypeScript binding | Untyped JavaScript binding |
|------------|--------------|----------------|--------------------|----------------------------|
| Strong typing | JSpecify, NullAway, Error Prone, `javac` | mypy or pyright in strict mode | `strict`, `exactOptionalPropertyTypes`, `noUncheckedIndexedAccess`, optional `ts-reset` | JSDoc plus `tsc --checkJs`, weakest by default |
| L1 contracts | JML where useful, Bean Validation at input/output boundaries, explicit guards | icontract, deal, Pydantic, typed assertions, or runtime guards | Zod or Valibot at boundaries, assertion functions, strict domain types | Zod or Valibot plus runtime assertions |
| L2 properties | jqwik | Hypothesis | fast-check | fast-check |
| L3 proof | OpenJML or KeY where feasible | No default provider; cap at L2 unless a repo declares one | No default provider; cap at L2 unless a repo declares one | No default provider; cap at L2 |

A deterministic **assurance-classifier** gates the completion path. The
classifier is language-agnostic in concept and per-pack in detection patterns.
It detects these surfaces:

- security boundaries: authorization guards, route guards, policy decisions,
  authentication state, credential handling, tenant isolation, and trust
  boundary parsers;
- state machines: transition tables, lifecycle enums, `canTransitionTo` style
  methods, reducer state transitions, workflow phase transitions, and
  status-changing service methods;
- DAG and graph logic: reachability, topological sort, cycle detection,
  dependency traversal, and traceability graph mutation;
- corruption-prone mutators: methods that update cross-field invariants,
  irreversible state, audit history, graph links, persistent identity, or
  serialized value bags.

The classifier auto-excludes trivial surfaces: DTOs, records, pure config,
constant-only enums, pure tag enums, generated API mirrors, schema-only files,
and glue code with no behavior. The provider pack may add explicit exclusions
for language or framework idioms, but exclusions must be deterministic and
auditable.

The completion gate refuses a change when the classifier mandates L1 or L2 and
the matching contract plus test artifact is absent. For L1 this means the
language binding's contract or boundary validation plus at least one
contract-exercising test. For L2 this means the L1 artifact plus a
property-based test, state-space test, or declared equivalent. The gate returns
a structured refusal that names the surface, missing artifact, and next action.

When a binding is unavailable, the workflow degrades gracefully:

- no contract library: require runtime validation at the boundary plus a test;
- no property-based testing provider: cap the surface at L1 and record the
  unavailable L2 provider;
- no prover: do not claim L3 and treat L1 contracts as test-backed and
  documentation-grade;
- untyped JavaScript: treat runtime schemas as the contract and nudge toward
  TypeScript or `tsc --checkJs`.

ADR-012 becomes the Java binding for this language-neutral ladder. Phase 1 must
reconcile the documented coverage drift in `docs/CODING_STANDARDS.md`, which
still states a 30 percent pre-alpha coverage threshold while the active JaCoCo
gate is 80 percent.

## Consequences

The workflow can enforce the same assurance property across Java, Python,
TypeScript, JavaScript, and infrastructure repositories without adding a new
skill lane.

Contract discipline moves from agent prose to a deterministic classifier and
completion gate. Security boundaries, state machines, DAG logic, and
corruption-prone mutators cannot ship without the contract and test artifacts
that the level requires.

Provider packs carry the language-specific details. A repository can adopt the
assurance ladder without pretending that JML or OpenJML apply to every
ecosystem.

The workflow remains honest about proof strength. Outside the prover's actual
scope, contracts support tests and review but do not become formal proof.

The classifier adds maintenance work. Each gate pack must keep detection
patterns current with its language and framework conventions, and false
positives must be corrected in the pack instead of bypassed in agent prose.

## References

- ADR-012: Formal Methods Development Process.
- ADR-014: Pluggable Verification Architecture.
- ADR-058: Gate capability-to-provider indirection and gate packs.
- ADR-059: The engineering contract.
