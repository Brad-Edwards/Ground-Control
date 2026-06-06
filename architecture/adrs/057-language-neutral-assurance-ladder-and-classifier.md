# ADR-057: Language-neutral assurance ladder and classifier

## Status

accepted

## Date

2026-06-06

## Context

ADR-012 defines the earlier Ground Control assurance levels, but it binds the
current process to the Java Virtual Machine (JVM), Java Modeling Language
(JML), OpenJML, and jqwik. The `/implement` workflow must also serve Rust,
Python, TypeScript, C and C++, documentation-first repositories, and mixed
repositories that combine several ecosystems in one change.

A language-specific assurance rule does not preserve ADR-027's
agent-neutral workflow boundary. The engine must name assurance properties.
Gate packs bind those properties to tools for a repository and path scope.

The existing pre-alpha default also leaves important surfaces to prose
classification. Security boundaries, state machines, directed acyclic graph
(DAG) operations, and corruption-prone domain mutators need explicit contracts
and tests before the completion gate accepts the change. Trivial data transfer
objects (DTOs), records, configuration, pure tag enums, and generated schema
mirrors must stay out of that requirement so the workflow does not add
ceremony where no behavior exists.

OpenJML Extended Static Checking (ESC) currently reaches only a narrow Java
surface. Outside that scope, L1 contracts still provide a valuable oracle for
tests and reviewers, but they are test-backed and documentation-grade rather
than machine-proven.

## Decision

The assurance ladder is language-neutral. Each rung names a required assurance
property; each repository binds the property to concrete tooling through its
selected gate packs.

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

The initial supported pack families are:

- `rust-cargo`
- `jvm-gradle`
- `jvm-maven`
- `node-ts`
- `cpp-cmake`
- `python`
- `docs-generic`

Concrete language and provider examples live in the pack registry defined by
ADR-062 and detailed in ADR-058. That registry is non-exhaustive at the tool
level. For example, `jvm-gradle` may bind L2 to jqwik, `python` may bind L2 to
Hypothesis, `node-ts` may bind L2 to fast-check, `rust-cargo` may bind L2 to
proptest or quickcheck, and `cpp-cmake` may bind L2 to fuzz or property
harnesses. The engine does not prefer any of those providers.

A deterministic **assurance classifier** gates the completion path. The
classifier is language-neutral in concept and per-pack in detection patterns.
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
constant-only enums, pure tag enums, generated mirrors, schema-only files, and
glue code with no behavior. The provider pack may add explicit exclusions for
language or framework idioms, but exclusions must be deterministic and
auditable.

The `docs-generic` pack is the explicit code-light binding. On documentation,
policy, and configuration-only diffs, its assurance classifier no-ops for
`contract_boundary`, `property_verification`, `mutation`, `type_safety`,
`architecture`, and `accessibility` unless the repository selects an additional
code pack for the path. It may still run `docs_policy`, `secret_scan`,
`dependency_policy`, `policy`, and `traceability` gates.

The completion gate refuses a change when the classifier mandates L1 or L2 and
the matching contract plus test artifact is absent. For L1 this means the
pack's contract or boundary-validation artifact plus at least one
contract-exercising test. For L2 this means the L1 artifact plus a
property-based test, state-space test, fuzz harness, or declared equivalent.
The gate returns a structured refusal that names the surface, missing artifact,
and next action.

When a binding is unavailable, the workflow degrades gracefully:

- no contract library: require runtime validation at the boundary plus a test;
- no property-based testing provider: cap the surface at L1 and record the
  unavailable L2 provider;
- no prover: do not claim L3 and treat L1 contracts as test-backed and
  documentation-grade;
- code-light path: record `not_applicable` or `provider_missing` according to
  the manifest, then route any residual concern to the reviewer lens.

ADR-012 remains accepted as the Java and JVM binding for this
language-neutral ladder. It does not define the platform-wide assurance model.

## Consequences

The workflow can enforce the same assurance property across languages and
code-light repositories without adding a new workflow lane.

Contract discipline moves from prose to a deterministic classifier and gate
runner. Security boundaries, state machines, DAG logic, and corruption-prone
mutators cannot ship without the contract and test artifacts that the level
requires.

Provider packs carry the ecosystem-specific details. A repository can adopt
the assurance ladder without pretending that any one contract language,
property-test library, or prover applies everywhere.

The workflow remains honest about proof strength. Outside a prover's actual
scope, contracts support tests and review but do not become formal proof.

The classifier adds maintenance work. Each gate pack must keep detection
patterns current with its language and framework conventions, and false
positives must be corrected in the pack instead of bypassed in agent prose.

## References

- ADR-012: Formal Methods Development Process.
- ADR-014: Pluggable Verification Architecture.
- ADR-058: Gate manifest, runner contract, and gate-pack bundles.
- ADR-059: The engineering contract.
- ADR-062: Portable /implement engine, gate-pack registry, and consumer
  adoption model.
