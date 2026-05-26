# ADR-012: Formal Methods Development Process

## Status

Accepted

## Date

2026-03-08

## Context

Ground Control requires a **process** governing when and how to apply formal methods—not just a list of tools. ADR-003 (now superseded by ADR-013) established design-by-contract as a principle; this ADR codifies the methodology.

Industry precedent from Amazon s2n (specification-driven TLS), SPARK Ada, Dafny, and Frama-C demonstrates that **Specification-Driven Development (SDD)** (writing formal specifications before implementation) catches design errors earlier and at lower cost than test-after workflows.

This ADR codifies an SDD methodology for Ground Control. The process and assurance levels are **language-agnostic methodology**—the level definitions (L0-L3) apply universally regardless of target language. The tooling section reflects the current Java/Spring Boot stack (ADR-013) used for Ground Control's own code. ADR-014 generalizes the tooling to a pluggable verification architecture for the platform's polyglot output.

### Default Level Rationale

#### Pre-alpha (current): L0 default

During pre-alpha, the default assurance level is **L0 (Standard)**. The goal is development velocity—getting the platform's differentiating features (graph-based traceability, verification orchestration) built and working. The real cost of higher assurance levels is not writing contracts or tests but **waiting for tests to run**, which blocks the rapid iteration loop needed when the architecture is still taking shape.

Escalate above L0 only when the code genuinely demands it:
- **L1** for state transitions, security boundaries, and methods with non-obvious preconditions that would cause silent corruption if violated
- **L2** for the state machine and DAG operations (these have already proven their value via jqwik)

JML contracts already present in the codebase are retained—they serve as documentation even without strict test-per-contract enforcement.

#### Post-alpha target: L1 default

Once the platform's core features are operational and the CI pipeline can run verification autonomously, the default will rise to L1. The rationale for L1-as-default remains sound:

1. **Contracts are near-zero cost for an LLM.** Writing `// @ requires x != null;` takes one line.
2. **Contracts catch LLM drift.** A second specification channel—if the implementation doesn't satisfy the contract, the build fails.
3. **"Adoption friction" doesn't exist.** An AI agent follows the process it's given.
4. **The cost of a missed contract is higher than the cost of writing one**—once the machinery is running.

The transition from L0-default to L1-default will be a deliberate decision documented in a follow-up ADR when the platform reaches beta.

## Decision

### SDD Workflow

The core development loop for all L1+ code is:

1. **Classify**—Determine the assurance level before writing any code (see decision table below).
2. **Spec**—Write JML contracts (`requires`, `ensures`, `invariant`) that express the behavioral intent.
3. **Test**—Write a failing test that exercises the contract (both happy-path and contract-violation).
4. **Code**—Implement the method body to satisfy the contracts and pass the test.
5. **Verify**—Run `make policy` and `./gradlew check` (repo guardrails + compilation + OpenJML + tests + Spotless + ArchUnit).

This is "TDD for invariants": contracts are the failing specification, implementation satisfies them. Contracts and tests are complementary—contracts define *what must always hold*, tests verify *specific scenarios*.

### Assurance Levels

| Level | Name | Scope | Tools |
|-------|------|-------|-------|
| 0 | Standard | **Pre-alpha default.** All code unless escalated by the decision table below | `javac` + JUnit 5 |
| 1 | Contracted | State transitions, security boundaries, methods with non-obvious preconditions that cause silent corruption | + JML `requires`/`ensures`/`invariant` |
| 2 | Property-Verified | State machines, transition tables, DAG operations | + jqwik + TLA+ design specs (ADR-014) |
| 3 | Formally Specified | Tenant isolation, access control, audit (future) | + KeY proofs (Isabelle/HOL secondary) |

**Pre-alpha default is L0.** Escalate to L1 for code where a missed precondition causes silent corruption. Escalate to L2 for state machines and graph operations. See "Default Level Rationale" above for the planned transition to L1-default at beta.

### Level Decision Table

Apply these rules in order. Use the first match.

| If you are writing... | Level |
|-----------------------|-------|
| A state machine, transition table, or workflow | **L2** |
| DAG operations (cycle detection, topological sort, reachability) | **L2** |
| Security boundary logic (auth checks, permission guards) | **L1** |
| A domain model method where invalid input causes silent data corruption | **L1** |
| Everything else | **L0** |

When in doubt, use L0 during pre-alpha. The bar rises at beta.

### Current Code Classification

| Code | Level | Notes |
|------|-------|-------|
| `domain/requirements/model/Requirement.java` | L2 | State machine + cross-field invariant (`archivedAt`/`status`) |
| `domain/requirements/model/RequirementRelation.java` | L1 | Self-loop prevention precondition |
| `domain/requirements/state/Status.java` | L2 | Transition table with jqwik property tests |
| `domain/requirements/state/ConfidenceLevel.java` | L0 | Pure value enum; ordinal-ordered band with a `compareTo`-based `atLeast`/`strongest` helper, no transitions or invariants (status-drift analysis support per ADR-011 §9) |
| `domain/requirements/state/StatusDriftSignal.java` | L0 | Pure value enum; `switch`-mapped `defaultConfidence()` accessor, no transitions or invariants (status-drift analysis support per ADR-011 §9) |
| `domain/evidence/state/EvidenceType.java` | L0 | Pure value enum; semantic role tag on `EvidenceArtifact` (GC-M016 / ADR-044), no transitions or invariants |
| `domain/evidence/state/EvidenceSourceKind.java` | L0 | Pure value enum; internal-vs-external source-reference discriminator (GC-M016 / ADR-044), with an `isInternal()` predicate but no state transitions |
| `domain/requirements/service/StatusDriftService.java` | L0 | Read-only derived analysis (ADR-011 §9); a service, not a state machine or security boundary—one-test-per-behavior is sufficient |
| `domain/exception/` | L0 | Data carriers only |
| `api/GlobalExceptionHandler` | L0 | Mapping layer, no domain logic |
| `api/ErrorResponse` | L0 | Record (DTO) |
| `shared/logging/RequestLoggingFilter` | L0 | Glue code |
| `infrastructure/` | L0 | Adapter layer |

### Triage Guide (pre-alpha)

**L2 (property tests + design specs):**
- State machine transitions and transition tables
- Graph/DAG operations

**L1 (contracts on critical paths):**
- Security boundaries (authentication, authorization checks)
- Domain model methods where invalid input causes silent data corruption (for example, status/archivedAt invariant)

**L0 (everything else):**
- Services, repositories, controllers, configuration, DTOs, glue code
- One test per significant behavior is sufficient—no two-tests-per-contract requirement during pre-alpha

### Tool Integration

**OpenJML ESC** (Extended Static Checking)—integrated in Phase 2-B:
- Version: OpenJML 21-0.21 (JDK 21 series)
- Gradle tasks: `./gradlew downloadOpenJml` (fetches binary), `./gradlew openjmlEsc` (runs Z3 prover)
- Defined in: `backend/gradle/openjml.gradle.kts`, applied from `build.gradle.kts`
- Scope: `domain/requirements/state/` and `domain/verification/state/` only (pure enums with no framework annotations). Other `state/` packages (`assets`, `controls`, `riskscenarios`, `riskcontrol`, `plugins`, `controlpacks`, `packregistry`, `threatmodels`, `findings`, `audits`) contain value enums not covered by ESC.
- `domain/threatmodels/state/` (`ThreatModelStatus`, `StrideCategory`, `ThreatModelLinkTargetType`, `ThreatModelLinkType`) is L0: `ThreatModelStatus` is a simple content lifecycle (DRAFT → ACTIVE → ARCHIVED) implemented with the same enum-transition pattern as `RiskScenarioStatus`, and the other three are pure value enums with no invariants. Threat modeling is a human-analysis surface per ADR-024; it does not need property-based verification at this assurance level. GC-H009 (vulnerability-threat linking) added `FINDING` to `ThreatModelLinkTargetType` as an internal target dispatched through `GraphTargetResolverService.validateThreatModelTarget`; the constant carries no transition logic or invariants and preserves the L0 classification.
- `domain/findings/state/` (`FindingStatus`, `FindingType`, `FindingSeverity`, `FindingLinkTargetType`, `FindingLinkType`) is L2 for `FindingStatus` and L0 for the other four. `FindingStatus` carries a 4-state remediation lifecycle (`OPEN → REMEDIATION_IN_PROGRESS → REMEDIATION_COMPLETE → VERIFIED_CLOSED`, with a `REMEDIATION_COMPLETE → REMEDIATION_IN_PROGRESS` reopen edge when verification rejects the claimed remediation; `VERIFIED_CLOSED` is terminal). Transitions are covered by `FindingStatusPropertyTest` (jqwik property test over the full state DAG plus an entity-level test for the null-target guard and detail-map exception shape), per ADR-038. The remaining enums are pure value types with no invariants.
- `domain/audits/state/` (`AuditStatus`, `AuditType`, `AuditPhaseKind`, `AuditLinkTargetType`, `AuditLinkType`) is L2 for `AuditStatus` and L0 for the other four. `AuditStatus` carries a 5-state audit lifecycle (`PLANNED → IN_PROGRESS → DRAFT_REPORT → FINAL_REPORT → CLOSED`, with a `FINAL_REPORT → DRAFT_REPORT` rework loop; `CLOSED` is terminal). Transitions are covered by `AuditStatusPropertyTest` (jqwik property test over the full state DAG plus an entity-level test for the null-target guard and detail-map exception shape), per ADR-047. The remaining enums are pure value types with no invariants.
- `domain/controls/state/` adds `ControlTestMethodology` (PCAOB AS 2201 vocabulary), `ControlTestConclusion`, and `ControlEffectivenessRating` per ADR-039 (GC-I012/GC-I013). All three remain L0: pure value enums with no transitions or invariants; the only semantic check is "the bound value is one of these names," which Jackson's enum binding plus `GlobalExceptionHandler`'s 422 envelope already cover. Control verification (test execution and effectiveness rating) is a human-analysis surface analogous to threat modeling; it does not need property-based verification.
- `domain/assets/state/` adds `AssetCriticality` (CRITICAL/HIGH/MEDIUM/LOW), `AssetEnvironment` (PRODUCTION/STAGING/DEVELOPMENT/TEST/NON_PRODUCTION/OTHER), and `AssetScope` (IN_SCOPE/OUT_OF_SCOPE) per GC-M012. All three remain L0: pure value enums dispatched through Jackson binding into the standard `GlobalExceptionHandler` envelope; no transitions, no invariants. Asset ownership and stewardship persist as bounded free-text fields on `OperationalAsset` (not enums), so there is no associated state surface to classify. GC-M011 adds `AssetSubtypeSchemaStatus` (`ACTIVE`, `DEPRECATED`); mirrors `MethodologyProfileStatus` (a registry-entry lifecycle, not a domain-state transition). The one-ACTIVE-per-`(project, assetType, subtype)` invariant lives in `AssetService.registerSubtypeSchema` rather than the enum, so the enum itself stays L0; metadata bounds and schema validation belong to `AssetSubtypeValidator` (also L0—bounded-input value-validation only). The asset's own `subtype` and `metadata` are bounded free-text / value-bag fields on `OperationalAsset`, not enums. GC-M018 adds `KnowledgeState` (`CONFIRMED`, `PROVISIONAL`, `UNKNOWN`) per ADR-046: pure value enum with an `atLeast` comparator backed by an explicit-strength numeric field (declared explicitly rather than reading `Enum.ordinal()` because errorprone's `EnumOrdinal` flags ordinal-based comparison as a silent-reorder hazard). Same surface as the GC-M012 trio—no transitions, no invariants on the enum; Jackson binding routes invalid values into the standard `GlobalExceptionHandler` envelope. The state itself is mirrored on both `OperationalAsset` and `AssetRelation` as a NOT NULL column with a DB default of `'CONFIRMED'` so legacy rows back-fill (V092 parent, V093 Envers parity); the entity initializer sets `CONFIRMED` for freshly constructed objects.
- `domain/testcases/state/` adds `TestCaseStatus` (4-state lifecycle `DRAFT → APPROVED → DEPRECATED → ARCHIVED` with revive edges `DEPRECATED → APPROVED` and a terminal `ARCHIVED`), `TestCaseType` (`MANUAL`, `AUTOMATED`, `HYBRID`), and `TestCasePriority` (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`) per ADR-040 (TC-001). `TestCaseStatus` carries a transition table on the enum itself (`canTransitionTo` / `validTargets`); the full from→to matrix is exhaustively covered by `TestCaseStatusTest` (every valid edge and a generated cross-product for invalid edges, including the terminal-state and null-target guards). Invalid transitions surface as `DomainValidationException` from `TestCase.transitionStatus` and route through `GlobalExceptionHandler` to a 422 envelope; no silent-corruption pathway exists. Classification: `TestCaseStatus` is L0 because the matrix test plus the entity-level rejection already constitute the verification contract for a 4-node DAG. `TestCaseType` and `TestCasePriority` are pure value enums with no invariants (L0). TC-004 adds `TestCaseFormat` (`STEP_BASED`, `GHERKIN`)—a pure value enum with no transitions, used as the immutable authored-format discriminator on `TestCase`. Classification: L0. The format-vs-children invariant lives in the services (`TestCaseStepService.create` rejects on a non-`STEP_BASED` parent; `TestCaseGherkinService.requireGherkinTestCase` rejects on a non-`GHERKIN` parent), not on the enum. TC-006 adds `TestPlanStatus` (5-state execution lifecycle `DRAFT → ACTIVE → IN_PROGRESS → COMPLETED → ARCHIVED`, with `IN_PROGRESS → ACTIVE` re-pause and `COMPLETED → ACTIVE` re-open edges; `ARCHIVED` is terminal) per ADR-044. `TestPlanStatus` follows the same enum-transition pattern as `TestCaseStatus` (`canTransitionTo` / `validTargets`), with the full from→to matrix exhaustively covered by `TestPlanStatusTest` (every valid edge plus per-node invalid-edge guards including the terminal-state and null-target cases). Invalid transitions surface as `DomainValidationException` from `TestPlan.transitionStatus` and route through `GlobalExceptionHandler` to a 422 envelope. Classification: L0—the matrix test plus the entity-level rejection constitute the verification contract for a 5-node DAG with bounded backwards arcs. TC-007 adds `TestSuitePopulationMode` (`STATIC`, `REQUIREMENTS_BASED`, `QUERY_BASED`) per ADR-047—a pure value discriminator with no transitions (mode is set on create and immutable, enforced by the absence of a setter on `TestSuite` plus a CHECK constraint at the SQL layer). Mode-specific field invariants and mode-mismatch operation rejections live in `TestSuite` and `TestSuiteService`, not on the enum. Classification: L0—value enum, no DAG to verify. TC-008 adds `TestRunStatus` (5-state run lifecycle `PLANNED → IN_PROGRESS → COMPLETED → ARCHIVED`, with `PLANNED → ABORTED` / `IN_PROGRESS → ABORTED` aborts and `ARCHIVED` terminal) and `TestRunCaseResultStatus` (`NOT_RUN`, `PASSED`, `FAILED`, `BLOCKED`, `SKIPPED`) per ADR-049. `TestRunStatus` follows the same enum-transition pattern as `TestPlanStatus` / `TestCaseStatus` (`canTransitionTo` / `validTargets`) but has no backwards arcs out of `COMPLETED` or `ABORTED` because a run is a single execution pass against a frozen snapshot—re-running is a new run, not a status flip. The full from→to matrix is exhaustively covered by `TestRunStatusTest` (every valid edge plus per-node invalid-edge guards including the terminal-state and null-target cases). Invalid transitions surface as `DomainValidationException` from `TestRun.transitionStatus` and route through `GlobalExceptionHandler` to a 422 envelope. Classification: L0—the matrix test plus the entity-level rejection constitute the verification contract for a 5-node DAG. `TestRunCaseResultStatus` is a pure value enum with no transition graph (per-case results may flip freely as re-tests and unblocks happen over the life of a run); classification L0.
- `domain/riskcontrol/state/` adds `MappingControlRole` (`PREVENTIVE`, `DETECTIVE`, `CORRECTIVE`, `DETERRENT`, `COMPENSATING`, `RECOVERY`, `DIRECTIVE`) per GC-T003 (ADR-052). Pure value enum used as the typed role tag on `RiskControlMapping`; no state transitions or invariants. Classification: L0—Jackson binding plus `GlobalExceptionHandler`'s 422 envelope constitute the full verification contract.
- `domain/riskscenarios/state/` adds `ActionItemStatus` (`PLANNED`, `IN_PROGRESS`, `BLOCKED`, `DONE`, `CANCELED`) per GC-T004 / C6 (issue #862). Pure value enum used as the typed status tag on per-action-item value records carried in `TreatmentPlan.actionItems`. Distinct from `TreatmentPlanStatus`, which carries the plan-level lifecycle with its own transition rules—action-item status flips freely as work progresses, and `DONE` at the item level is not the same as `COMPLETED` at the plan level. No transitions, no invariants on the enum itself. Classification: L0—Jackson binding plus `GlobalExceptionHandler`'s 422 envelope constitute the full verification contract; per-item required-field rejection lives in Bean Validation on the request DTO and a service-layer guard in `TreatmentPlanService.validateActionItems`, not on the enum.
- `domain/packregistry/state/` now includes generic-registry value enums such as `PackType`, `CatalogStatus`, `TrustOutcome`, `InstallOutcome`, `TrustPolicyField`, and `TrustPolicyRuleOperator`. They improve schema clarity and policy typing, but remain L0 because they contain no transition logic or non-trivial invariants.
- `TrustPolicyField` distinguishes informational integrity facts such as `signatureVerified` from policy-safe trust anchors such as `signerTrusted`; enforcement of unsupported fields/operators remains in `TrustPolicyService` rather than the enum itself.
- Gradle up-to-date checking: skips when source files haven't changed (~1 s no-op)
- **Known limitations**: OpenJML's bundled `CharSequence.jml` spec has invariant bugs that cause false positives on classes with `String` constructor parameters. JPA entities fail due to Hibernate's no-arg constructor leaving fields `null`. These classes remain at L1 (contract + test pairs). See `docs/CODING_STANDARDS.md` "OpenJML ESC Scoping" for design guidelines.

**OpenJML RAC** (Runtime Assertion Checking):
- Gradle task: `./gradlew openjmlRac` (compiles domain code with embedded assertions to `build/classes/rac`)
- Scope: full `domain/` package
- Status: task defined, not yet wired into test execution

**jqwik**: Property-based tests tagged `@Tag("slow")`. Run in CI, skippable locally via Gradle task filtering.

**TLA+** (design-level verification)—adopted per ADR-014:
- TLC model checker for exhaustive state space exploration
- Specs in `specs/tla/` (versioned with code)
- Scope: state machines, DAG invariants, materialization consistency, traceability completeness
- Complements code-level verification (JML/OpenJML) with design-level verification
- Higher ROI than expanding OpenJML ESC to additional classes

**KeY** (future): Formal proofs for L3 code. Deferred until L1 and L2 coverage is established.

### Drift Detection

During pre-alpha, the drift detection bar is relaxed:

1. **L1+ contracts should have at least one test** covering the contracted behavior. The strict two-tests-per-contract rule (happy-path + violation) applies post-alpha.
2. **OpenJML ESC re-verification** runs in CI on the `state/` package.
3. **L2 property tests** (jqwik) run in CI, tagged `@Tag("slow")` so they can be skipped locally for fast iteration.

Existing JML contracts without tests are acceptable during pre-alpha—they still serve as documentation. The post-alpha process will require test coverage for all contracts.

Repo-native guardrails complement the formal-methods toolchain. They enforce that workflow, migration, controller, and ADR changes stay synchronized with the documents and policy artifacts that make the methodology legible to both humans and agents.

### Relationship to TDD

SDD extends TDD by adding contracts as a specification layer:

- **TDD**: "Write a failing test, then make it pass."
- **SDD (post-alpha)**: "Write a failing contract, write a test that exercises it, then make both pass."
- **Pre-alpha**: Write code, add contracts where they prevent silent corruption, write tests for significant behaviors. Pragmatism over ceremony.

## Consequences

### Positive

- Bugs surface at contract boundaries with clear error messages, not as downstream corruption
- Spec-first catches design errors before code is written
- L1 default ensures contracts are written as a matter of course, not as an afterthought
- AI-generated code is validated against a second specification channel (contracts), catching implementation drift
- OpenJML ESC finds contract violations statically, without running the code
- JML contracts serve as executable documentation
- Single annotation language (JML) spans L1 through L3—no tool-boundary translation

### Negative

- CI time increases with OpenJML ESC verification
- More JML annotations to maintain than with an L0 default
- jqwik property tests are slower than example-based JUnit tests

### Risks

- OpenJML + Hibernate proxies: JPA entity proxies may interfere with JML runtime checks. Mitigated by scoping RAC to domain logic methods, not framework-generated code.
- Over-contracting L0 code: enthusiasm may lead to contracts on configuration or DTOs. Mitigated by explicit L0 classification in the decision table—if it matches an L0 rule, do not contract it.

## Implementation Status

- Assurance levels L0-L3 are codified as the `AssuranceLevel` enum in `domain/verification/state/AssuranceLevel.java`.
- Verification outcomes are codified as the `VerificationStatus` enum in `domain/verification/state/VerificationStatus.java`.
- Both enums are used by the `VerificationResult` entity (ADR-014 §2, GC-F001).
- Not every enum under `domain/**/state/` is a state machine. Tag enums that
  do not define a `canTransitionTo` lifecycle (for example,
  `ReassessmentTriggerCategory` and `ReassessmentTriggerTargetType` added for
  GC-T004 / C8 in issue #863) are L0 data classifiers, not L1+ contract
  surfaces; placement under `state/` follows the existing repo convention for
  domain-enum location, not an assertion that JML contracts apply.

## Related ADRs

- **ADR-013** (Java/Spring Boot Backend Rewrite)—Current tool names (JML, OpenJML, jqwik, KeY) for Ground Control's own code.
- **ADR-014** (Pluggable Verification Architecture)—Generalizes assurance levels from JML-specific to pluggable. The methodology defined here is universal; the tools per level depend on the target language. Adds TLA+ at L2 for design-level verification.
