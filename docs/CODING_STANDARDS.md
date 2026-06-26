# Ground Control - Coding Standards

These are mandatory rules. Follow them exactly when writing or modifying code.

## Development Workflow

Ground Control uses a phased approach to formal methods rigor. During **pre-alpha** (current), the priority is velocity - get the differentiating features built. The bar rises at beta.

### Pre-alpha workflow

1. **Write code.** Implementation first. Get it working.
2. **Add JML contracts where they prevent silent corruption** - state transitions, security boundaries, cross-field invariants. Not on every method.
3. **Write one test per significant behavior.** No two-tests-per-contract requirement. No mandatory violation tests for simple CRUD.
4. **Run `make rapid`.** Format + compile in ~3–5 s. Tests run in CI.
5. **Run `make policy`** when you changed workflow, ADR, controller, migration, MCP, or PR-policy surfaces.

### Assurance levels

| Level | Name | When to use (pre-alpha) | What you must produce |
|-------|------|-------------------------|----------------------|
| L0 | Standard | **Default.** Everything unless escalated below | Working code + one test per significant behavior |
| L1 | Contracted | State transitions, security boundaries, methods where invalid input causes silent data corruption | JML contracts + tests for the contracted behavior |
| L2 | Property-Verified | State machines, DAG operations | L1 + jqwik property tests |
| L3 | Formally Specified | Future | L2 + KeY/Lean proofs |

**When in doubt, use L0.** Ship features. The bar rises at beta.

### Decision rules

| If you are writing... | Level |
|-----------------------|-------|
| A state machine, transition table, or workflow | **L2** |
| DAG operations (cycle detection, topological sort, reachability) | **L2** |
| Security boundary logic (auth checks, permission guards) | **L1** |
| A domain model method where invalid input causes silent data corruption | **L1** |
| Everything else | **L0** |

### JML contracts (when writing L1+ code)

JML annotations use block comment syntax (`/*@ @*/`). Do not use `// @` - it is not valid JML and OpenJML will not parse it.

```java
/*@ requires newStatus != null;
  @ requires status.canTransitionTo(newStatus);
  @ ensures status == newStatus; @*/
public void transitionStatus(Status newStatus) { ... }
```

For method modifiers (`pure`, `non_null`), use inline annotations:

```java
public /*@ pure @*/ Set<Status> validTargets() { ... }
```

- `/*@ requires @*/` for preconditions
- `/*@ ensures @*/` for postconditions
- `/*@ public invariant @*/` for class invariants
- `/*@ pure @*/` on methods called from JML expressions
- `/*@ spec_public @*/` on private fields referenced in public specs
- Keep conditions simple and side-effect-free
- Files with JML annotations need `@SuppressWarnings("java:S125")` to suppress SonarQube false positives (except files in ESC scope that use only inline annotations)

### Post-alpha target

At beta, the default rises to L1. Every L1+ public method gets contracts, every contract gets a happy-path and violation test, and the full SDD loop (Classify → Spec → Test → Code → Verify) becomes mandatory. See ADR-012 for the full rationale.

## Architecture Rules

These rules are enforced by ArchUnit tests in `src/test/java/.../architecture/ArchitectureTest.java`. Violations fail the build.

### Dependency rule

```
api/ → domain/ ← infrastructure/
```

| Rule | Meaning | Consequence of violation |
|------|---------|------------------------|
| `domain/` must not import `api/` | Domain logic is framework-independent | Build fails |
| `domain/` must not import `infrastructure/` | Domain logic has no external adapter dependencies | Build fails |
| `api/` must not import `infrastructure/` | Controllers talk to domain, not adapters | Build fails |
| All exceptions must extend `GroundControlException` | Uniform error handling | Build fails |

### When to add new ArchUnit rules

Add a new `@ArchTest` rule to `ArchitectureTest.java` whenever you:
- Add a new top-level package (enforce its dependency constraints)
- Introduce a naming convention that must hold project-wide
- Add a new annotation that must only appear in specific layers
- Discover a dependency violation pattern that could recur

ArchUnit rules are JUnit 5 tests. They run with `./gradlew test`. No separate tooling.

## Repo Policy Guardrails

The repository also enforces ADR-aligned workflow policy outside Java:

- `architecture/policies/adr-policy.json` defines machine-readable guardrails
- `python3 bin/policy` enforces changed-file policies for ADR sync, controller/MCP/docs parity, migration companion updates, and PR body structure
- `make policy` is required before completion for both Claude and Codex work in this repo

## Package Structure

```
backend/src/main/java/com/keplerops/groundcontrol/
├── domain/           # Business logic. No Spring web imports.
│   └── requirements/
│       ├── model/        # JPA entities with JML contracts
│       ├── state/        # Enums (Status, RequirementType, Priority, RelationType)
│       ├── service/      # Domain services (write-owners of entities)
│       └── repository/   # Spring Data JPA interfaces
│   └── exception/       # Domain exception hierarchy (shared across all domain areas)
├── api/              # REST controllers only. Thin handlers that delegate to services.
├── infrastructure/   # External adapters (AGE graph, external APIs)
└── shared/           # Cross-cutting concerns (logging filters, utilities)
```

**Placement rules:**
- New domain concept? Create a new sub-package under `domain/` following the same structure.
- New REST endpoint? Add a controller in `api/`. It must only call domain services.
- New external integration? Add an adapter in `infrastructure/`. Domain defines the interface.
- New cross-cutting concern? Add to `shared/`.

## Exceptions

All application exceptions must extend `GroundControlException`. This is enforced by ArchUnit.

| Exception | `error_code` | HTTP Status | When to throw |
|-----------|-------------|-------------|---------------|
| `NotFoundException` | `not_found` | 404 | Entity lookup returns empty |
| `DomainValidationException` | `validation_error` | 422 | Business rule violation, invalid state transition |
| `AuthenticationException` | `authentication_error` | 401 | Missing or invalid credentials |
| `AuthorizationException` | `authorization_error` | 403 | Authenticated but not permitted |
| `ConflictException` | `conflict` | 409 | Duplicate key, optimistic lock failure |
| `GroundControlException` | `ground_control_error` | 500 | Fallback for unclassified domain errors |

**Rules:**
- Domain layer throws these exceptions. Never throw `ResponseStatusException` or Spring HTTP exceptions from domain code.
- `GlobalExceptionHandler` maps them to the `{"error": {"code", "message", "detail"}}` JSON envelope. Do not create additional exception handlers.
- `DomainValidationException` accepts a `Map<String, Object> detail` for structured error context. Use it.
- Never catch `Exception` broadly. Catch the specific exception you expect.
- Wrap external library exceptions in `infrastructure/` - domain code must never leak third-party exception types.

## JML Contract Reference

JML annotations use block comment syntax (`/*@ @*/`). Do not use `// @` - it is not valid JML and OpenJML will not parse it.

```java
// Class invariant - must hold after every public method returns
/*@ public invariant archivedAt == null || status == Status.ARCHIVED; @*/

// Precondition - must be true when the method is called
// Postcondition - must be true when the method returns
/*@ requires newStatus != null;
  @ requires status.canTransitionTo(newStatus);
  @ ensures status == newStatus; @*/

// Inline non-null annotation
public void transitionStatus(/*@ non_null @*/ Status newStatus) { ... }
```

**When to write JML:**
- Every L1+ public method gets `requires` and `ensures`. No exceptions.
- Every L1+ class with cross-field constraints gets a `public invariant`
- Do NOT write JML on `@Configuration` classes, records with no methods, filters, or test code

## OpenJML ESC Scoping

OpenJML Extended Static Checking (ESC) runs the Z3 SMT solver to formally prove JML contracts hold. However, OpenJML's bundled specs for `java.lang.CharSequence` and `java.lang.String` have invariant bugs that produce false positives when verifying classes that pass `String` parameters through constructors. JPA entities also fail ESC due to the no-arg constructor required by Hibernate (all fields are `null` after construction, triggering `NullField` violations).

### What gets L2 ESC verification

ESC runs on **pure logic classes** with no String constructor parameters and no framework annotations:
- `domain/requirements/state/` - enums, state machines, transition tables. This now also includes `ConfidenceLevel` and `StatusDriftSignal` (status-drift analysis support per ADR-011 §9): pure value enums - `ConfidenceLevel` has a `compareTo`-based ordering helper, `StatusDriftSignal` a `switch`-mapped `defaultConfidence()` accessor - with no transitions or invariants. They are L0 in classification but, being inside this package, are proven trivially by ESC.
- `domain/verification/state/`: `VerificationStatus`, `AssuranceLevel` enums (simple value enums, L0)
- Future pure domain logic classes that follow the same pattern

Other `state/` packages contain simple value enums (L0) that are **not** ESC-verified:
- `domain/assets/state/`: `AssetType`, `AssetLinkTargetType`, `AssetLinkType`, `AssetRelationType`, `ObservationCategory`, plus the GC-M012 trio `AssetCriticality` / `AssetEnvironment` / `AssetScope`, plus the GC-M011 `AssetSubtypeSchemaStatus` (`ACTIVE → DEPRECATED`; mirrors `MethodologyProfileStatus`; pure value enum; the one-ACTIVE-per-`(project, assetType, subtype)` invariant lives in `AssetService.registerSubtypeSchema` rather than the enum, so the enum itself stays L0), plus the GC-M018 `KnowledgeState` (`CONFIRMED` / `PROVISIONAL` / `UNKNOWN`; pure value enum with an explicit-strength `atLeast` comparator - strength is declared as a numeric field rather than read from `Enum.ordinal()` because errorprone's `EnumOrdinal` flags ordinal-based comparison; no transitions, no invariants)
- `domain/controls/state/`: `ControlFunction`, `ControlStatus`, `ControlLinkTargetType`, `ControlLinkType`, `ControlTestMethodology`, `ControlTestConclusion`, `ControlEffectivenessRating` (ADR-039: pure value enums for GC-I012/GC-I013 - no transitions, no invariants)
- `domain/riskscenarios/state/` - risk scenario link and status enums; per GC-T004 / C6 adds `ActionItemStatus` (`PLANNED`, `IN_PROGRESS`, `BLOCKED`, `DONE`, `CANCELED`) as the typed status tag on `TreatmentPlan` action items. Distinct from `TreatmentPlanStatus` (item `DONE` ≠ plan `COMPLETED`); no transitions or invariants on the enum, L0. Per GC-T012 also adds `NormalizedConcept` (the ten cross-methodology risk concepts: `THREAT_SOURCE`, `THREAT_EVENT`, `VULNERABILITY_OR_EXPOSURE`, `ASSET`, `PROCESS_OR_OBJECTIVE`, `CONSEQUENCE_OR_EFFECT`, `CONTROL`, `LIKELIHOOD_OR_FREQUENCY`, `IMPACT_OR_LOSS_MAGNITUDE`, `TREATMENT`) and `CrosswalkVocabularySurface` (`INPUT_SCHEMA`, `OUTPUT_SCHEMA`, `TREATMENT_STRATEGY_VOCABULARY`); pure value enums classifying `CrosswalkEntry` rows on `MethodologyProfile`; no transitions or invariants on the enums; semantic validation lives in `MethodologyProfileService.validateCrosswalkEntries` (duplicate-tuple, surface-presence, field-path resolution, conversion-rule requires scale/units), not on the enum; L0.
- `domain/riskappetite/state/`: `RiskAppetiteProfileStatus` (`DRAFT`, `ACTIVE`, `RETIRED`; pure value enum for GC-T005 tagging a `RiskAppetiteProfile` version lifecycle; mirrors `MethodologyProfileStatus`. No transitions or invariants on the enum - uniqueness, non-overlapping `ACTIVE` effective windows, and the tolerance-threshold XOR/scale/probability rules live in `RiskAppetiteProfileService`; L0)
- `domain/riskcontrol/state/`: `MappingControlRole` (`PREVENTIVE`, `DETECTIVE`, `CORRECTIVE`, `DETERRENT`, `COMPENSATING`, `RECOVERY`, `DIRECTIVE`; pure value enum for GC-T003 - no transitions, no invariants; L0)
- `domain/plugins/state/`: `PluginType` (`EVIDENCE_COLLECTOR` included for GC-S001 adapter registration), `PluginLifecycleState` enums
- `domain/evidence/collection/iam/`: GC-S002 IAM evidence adapter specification - `IamEvidenceProvider` (`OKTA`, `AZURE_AD`, `AWS_IAM`; stable provider keys) and `IamEvidenceFamily` (the five evidence families as canonical `iam-*` scope types / output-schema ids, all `OBSERVATION_SUMMARY`) are pure value enums with no transitions or invariants; `IamEvidenceSpecification` is the normative entry point (versioned output schemas, descriptor capability sets, conformance check) over the GC-S001 `EvidenceCollectionAdapter` port - not a new adapter interface. L0.
- `domain/evidence/collection/cloud/`: GC-S003 cloud infrastructure evidence adapter specification - `CloudEvidenceProvider` (`AWS`, `AZURE`, `GCP`; stable provider keys) and `CloudEvidenceFamily` (the five evidence families as canonical `cloud-*` scope types / output-schema ids - security group configs, encryption-at-rest, logging configs, backup policies, compliance scan results - all `OBSERVATION_SUMMARY`, with the compliance scanner source carried as a summary field rather than a separate adapter type) are pure value enums with no transitions or invariants; `CloudEvidenceSpecification` is the normative entry point (versioned output schemas, descriptor capability sets, conformance check) over the GC-S001 `EvidenceCollectionAdapter` port - not a new adapter interface. L0.
- `domain/controlpacks/state/`: `ControlPackLifecycleState`, `ControlPackEntryStatus` enums
- `domain/packregistry/state/`: `PackType`, `CatalogStatus`, `TrustOutcome`, `InstallOutcome`, `TrustPolicyField`, `TrustPolicyRuleOperator` enums
- `domain/threatmodels/state/`: `ThreatModelStatus` (simple DRAFT→ACTIVE→ARCHIVED lifecycle, same pattern as `RiskScenarioStatus`), `StrideCategory`, `ThreatModelLinkTargetType` (now includes `FINDING` per GC-H009 - still a pure value enum, no invariants; dispatch lives in `GraphTargetResolverService`), `ThreatModelLinkType` (pure value enums, no invariants; ADR-024 treats threat modeling as an analysis surface at L0)
- `domain/findings/state/`: `FindingStatus` (the 4-state remediation lifecycle `OPEN → REMEDIATION_IN_PROGRESS → REMEDIATION_COMPLETE → VERIFIED_CLOSED` with a `REMEDIATION_COMPLETE → REMEDIATION_IN_PROGRESS` reopen edge; L2 - property-tested in `FindingStatusPropertyTest`, see ADR-038 and ADR-012), `FindingType`, `FindingSeverity`, `FindingLinkTargetType`, `FindingLinkType` (pure value enums, no invariants; L0)
- `domain/testcases/state/`: `TestCaseStatus` (4-state lifecycle `DRAFT → APPROVED → DEPRECATED → ARCHIVED` with `DEPRECATED → APPROVED` revive and a terminal `ARCHIVED`; L0 - exhaustively matrix-tested in `TestCaseStatusTest` and rejected with `DomainValidationException` at `TestCase.transitionStatus`, per ADR-040 / TC-001), `TestCaseType` (`MANUAL`, `AUTOMATED`, `HYBRID`), `TestCasePriority` (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`) - both pure value enums, no invariants, L0; `TestCaseFormat` (`STEP_BASED`, `GHERKIN`) - pure value enum, immutable on `TestCase` after create; format-vs-children invariant lives in the services (`TestCaseStepService.create` rejects on a non-`STEP_BASED` parent, `TestCaseGherkinService.requireGherkinTestCase` rejects on a non-`GHERKIN` parent), not on the enum; L0 - per ADR-042 / TC-004. `TestPlanStatus` (5-state execution lifecycle `DRAFT → ACTIVE → IN_PROGRESS → COMPLETED → ARCHIVED`, with `IN_PROGRESS → ACTIVE` re-pause and `COMPLETED → ACTIVE` re-open edges; `ARCHIVED` is terminal) - same enum-transition pattern as `TestCaseStatus`, exhaustively matrix-tested in `TestPlanStatusTest` and rejected with `DomainValidationException` at `TestPlan.transitionStatus`; L0 - per ADR-044 / TC-006. `TestSuitePopulationMode` (`STATIC`, `REQUIREMENTS_BASED`, `QUERY_BASED`) - pure value discriminator with no transitions; immutable on `TestSuite` after create (no setter; CHECK constraint at the SQL layer). Mode-specific field invariants and mode-mismatch operation rejections live in `TestSuite` / `TestSuiteService`, not on the enum; L0 - per ADR-047 / TC-007. `TestRunStatus` (5-state run lifecycle `PLANNED → IN_PROGRESS → COMPLETED → ARCHIVED`, with `PLANNED → ABORTED` / `IN_PROGRESS → ABORTED` aborts and `ARCHIVED` terminal; unlike `TestPlanStatus` there are **no** backwards arcs out of `COMPLETED` or `ABORTED` - a run is a single execution pass and re-running is a new run; L0 - exhaustively matrix-tested in `TestRunStatusTest` and rejected with `DomainValidationException` at `TestRun.transitionStatus`, per ADR-049 / TC-008). `TestRunCaseResultStatus` (`NOT_RUN`, `PASSED`, `FAILED`, `BLOCKED`, `SKIPPED`) - pure value enum with no transition graph; per-case results may flip freely as re-tests, descopes, and unblocks happen over the life of a run. L0 - per ADR-049 / TC-008
- `domain/evidence/state/`: `EvidenceType` (semantic role tag: `OBSERVATION_SUMMARY`, `CONTROL_TEST_SUMMARY`, `ASSURANCE_CONCLUSION`, `VERIFICATION_SUMMARY`, `ATTESTATION`, `MIXED`), `EvidenceSourceKind` (`OBSERVATION`, `CONTROL_TEST`, `CONTROL_EFFECTIVENESS_ASSESSMENT`, `VERIFICATION_RESULT`, `RISK_ASSESSMENT_RESULT`, `FINDING`, `ATTESTATION`, `EXTERNAL` with an `isInternal()` predicate). Pure value enums for GC-M016 / ADR-045 - no transitions and no invariants; append-only enforcement on the parent `EvidenceArtifact` lives in `EvidenceArtifactService.supersede`, not on the enum (L0).
- `domain/audits/state/`: `AuditStatus` (5-state audit lifecycle `PLANNED → IN_PROGRESS → DRAFT_REPORT → FINAL_REPORT → CLOSED`, with a `FINAL_REPORT → DRAFT_REPORT` rework loop; `CLOSED` is terminal; L2 - property-tested in `AuditStatusPropertyTest`, see ADR-047 and ADR-012), `AuditType` (`INTERNAL`, `EXTERNAL`, `REGULATORY`, `SPECIAL`), `AuditPhaseKind` (`PLANNING`, `FIELDWORK`, `REPORTING`, `FOLLOWUP`), `AuditLinkTargetType`, `AuditLinkType` (pure value enums, no invariants; L0).
- `domain/derivation/state/`: `DerivationScopeMode`, `SystemModelFactKind`, and `CaptureLimitReason` are pure value enums for GC-GRC-001 derivation scopes, normalized fact classes, and capture-limit metadata. Service validation owns path, commit, language, surface, and provenance constraints. No state transitions or invariants live on the enums; classification L0.

These pack-registry enums remain L0 typed value surfaces. Use them to remove stringly typed branching and policy fields in the generic registry, but do not expand ESC scope unless they gain real transition logic or invariants.
`TrustPolicyField` includes both informational fields (for example `signatureVerified`) and trust-safe fields (for example `signerTrusted`); validation that rejects unsupported trust-policy rules belongs in domain services, not in the value enum.

### What ESC cannot verify (and why)

- `domain/requirements/model/` - JPA entities (Hibernate no-arg constructor produces `NullField` false positives)
- `domain/exception/` - exception hierarchy (OpenJML `CharSequence.jml` spec bug on String constructors)
- `domain/requirements/service/` - services that take String parameters

These classes keep their JML contracts as documentation. Contracts are enforced by tests, not by ESC.

### Design guidelines for ESC-verifiable code

When writing new domain logic, prefer designs that isolate pure logic from String-heavy construction:

- **Prefer enums over String constants.** Enums verify cleanly; String constants do not.
- **Extract pure logic into separate classes/methods** that operate on enums, numbers, or domain types rather than Strings. These can be ESC-verified independently.
- **Do not distort code to avoid Strings.** If a method naturally takes a `String`, keep it. The cost of a readable API is worth more than an ESC proof on an awkward abstraction.
- **State machines, validation rules, and computations** should live in classes without String constructors so they remain ESC eligible.

## Testing

### Test organization

```
src/test/java/com/keplerops/groundcontrol/
├── unit/domain/       # No DB, no Spring context. Fast. Run always.
├── integration/       # Testcontainers PostgreSQL. Slow. Run in CI.
└── architecture/      # ArchUnit rules. Fast. Run always.
```

### Test requirements by assurance level (pre-alpha)

| Level | Required tests |
|-------|---------------|
| L0 | One test per significant behavior. Skip trivial getters/setters. |
| L1 | L0 + at least one test per JML contract |
| L2 | L1 + jqwik `@Property` tests (tagged `@Tag("slow")`, skippable locally) |
| L3 | Future |

Integration tests: write smoke tests to verify the feature works end-to-end. Exhaustive endpoint coverage is a post-alpha concern.

### Naming and style

- Test class: `FooTest` for unit, `FooIntegrationTest` for integration
- Test method: describes behavior, not implementation - `archiveFromDraftFails`, not `testArchiveMethod`
- Use `@Nested` classes to group related tests: `Defaults`, `StatusTransitions`, `Archive`
- Use AssertJ for assertions: `assertThat(x).isEqualTo(y)`, not JUnit `assertEquals`
- Use `assertThatThrownBy(() -> ...).isInstanceOf(...)` for exception tests

### jqwik property tests

Tag with `@Tag("slow")`. Provide an `@Provide` method for custom arbitraries.

```java
@Tag("slow")
class TransitionPropertyTest {

    @Provide
    Arbitrary<Status> statuses() {
        return Arbitraries.of(Status.values());
    }

    @Property
    void validTransitionAlwaysChangesStatus(
            @ForAll("statuses") Status source,
            @ForAll("statuses") Status target) {
        Assume.that(source.canTransitionTo(target));
        // ... assert the transition works
    }
}
```

### Coverage

- Pre-alpha: 30% minimum (current JaCoCo threshold). Will increase as the platform matures.
- Post-alpha targets: domain 80%, API/infrastructure 70%.

## Logging

Use SLF4J. Never `System.out.println()`.

```java
private static final Logger log = LoggerFactory.getLogger(RequirementService.class);

// Semantic event name, structured key-value pairs
log.info("requirement_created: uid={}", requirement.getUid());
```

**Rules:**
- Use semantic event names: `requirement_created`, `status_changed`, not `"Created a new requirement"`
- Never log secrets, tokens, passwords, or PII
- `RequestLoggingFilter` auto-binds `request_id` to MDC - do not set it manually
- Production uses JSON output (Logstash Encoder). Dev uses console. Selected by Spring profile.

## Java Style

- Formatting: Spotless + Palantir Java Format. Run `./gradlew spotlessApply` before committing.
- Line length: 120 (Palantir default). Do not override.
- Javadoc: on public API boundaries only. Do not add javadoc to private methods or obvious code.
- No `System.out.println()`, no `System.err.println()`.
- Use records for DTOs and value objects: `RequirementRequest`, `RequirementResponse`, `ErrorResponse`.
- Use `var` for local variables when the type is obvious from the right-hand side.
- Use `sealed` classes/interfaces when a type hierarchy is closed.

### Naming

| Element | Convention | Example |
|---------|-----------|---------|
| Packages | `lowercase` | `requirements` |
| Classes | `PascalCase` | `RequirementService` |
| Methods | `camelCase` | `createRequirement()` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_RETRY_COUNT` |
| Private fields | `camelCase` | `requirementType` |
| DTOs | Records | `RequirementRequest` |
| Test classes | `FooTest` / `FooIntegrationTest` | `RequirementTest` |

## Error Responses

All error responses use this envelope. Do not invent new formats.

```json
{
  "error": {
    "code": "not_found",
    "message": "Requirement REQ-42 not found",
    "detail": null
  }
}
```

- `code`: machine-readable, matches exception's `errorCode`
- `message`: human-readable description
- `detail`: optional `Map<String, Object>` with structured context (for example, `{"current_status": "DRAFT", "target_status": "ARCHIVED"}`)

## TypeScript / Frontend Style

The frontend (`frontend/`) is a React 19 / TypeScript 5 SPA. It follows these rules:

- Formatting and linting: Biome. Run `cd frontend && npm run format` and `npm run lint`.
- No `any` types. Use `unknown` and narrow.
- All API calls go through TanStack Query hooks - no manual `fetch` + `useState` patterns.
- Component files: `PascalCase.tsx`. Utility/hook files: `camelCase.ts`.
- Avoid framework lock-in for UI components - prefer shadcn/ui (copy-paste Radix primitives).
- Never `console.log()` in committed code.

## Static Analysis Thresholds (SonarCloud)

Ground Control runs SonarCloud against every project. Sonar way, the built-in
profile, leaves complexity and size rules tuned too loose to catch real
god-class and god-method problems. Every Ground Control repo runs a
tightened profile instead, or the closest analyzer-specific equivalent when a
rule is not available for the language at hand.

The thresholds below are the house targets. Apply them to every new project
brought onto SonarCloud. Sonar rule IDs vary across analyzers; the IDs
listed are the canonical reference for Java and Python.

### Cross-language thresholds (size, complexity, duplication)

| Bound | Threshold | Reference rule |
|-------|-----------|----------------|
| File size (LOC) | 500 | `python:S104` |
| Function or method length (LOC) | 100 | `python:S138` |
| Function parameter count | 7 (Java), 13 (Python) | `S107` |
| Nested control-flow depth | 4 | `python:S134` |
| Return statements per function | 3 | `python:S1142` |
| Cognitive complexity per function | 15 | `S3776` |
| String literal duplication | 3 occurrences | `S1192` |
| Regex complexity | 20 | `S5843` |

### Java-specific thresholds (god class, god method, OO depth)

| Bound | Threshold | Rule |
|-------|-----------|------|
| Inheritance depth | 5 | `java:S110` |
| `switch` case count | 30 | `java:S1479` |
| Assertions per test | 25 | `java:S5961` |
| God-class coupling | 20 | `java:S6539` |
| Brain method | nesting=3, cyclomatic=15, LOC=65, NODV=7 | `java:S6541` |
| Statements per line | 5 | `java:S8444` |

When porting these to a new analyzer, prefer the nearest concept (file LOC,
function length, nesting, cognitive complexity) over a perfect rule-ID match.
The intent is to bound module and method size; the specific rule is the
mechanism.

### Profile artifacts

Captured quality profile XML lives in [`tools/sonar/`](../tools/sonar/).
Each export is date-stamped and prior snapshots stay in place, so profile
drift remains auditable. See that directory's README for the capture and
restore procedure.

## Git & CI

- All code goes through PR targeting `dev`. No direct push to `main` or `dev`.
- PRs require: `./gradlew check` passes (build + spotlessCheck + test + jacocoTestReport).
- Commit messages: imperative mood. `Add risk scoring engine` not `Added risk scoring engine`.
- Pre-commit hooks run file checks + gitleaks + Spotless auto-format + `./gradlew check` (full CI-equivalent: build + tests + static analysis + coverage) + `./gradlew openjmlEsc` (formal verification of JML contracts in ESC scope) + Terraform fmt/validate + Checkov IaC security scanning (on `deploy/terraform/`). Do not bypass with `--no-verify`.

## Enum placement under `domain/**/state/`

The `state/` package holds two distinct kinds of enums and the formal-methods
classification depends on which kind:

- **State machines.** Enums that define a `canTransitionTo(...)` lifecycle,
  for example `TreatmentPlanStatus` and `ControlStatus`. These are L1+
  contract surfaces; transition rules must be unit-tested and ADR-012's L1
  rule applies.
- **Tag enums.** Enums that are pure classifiers with no transition surface,
  for example `AssetLinkTargetType`, the `ReassessmentTriggerCategory` /
  `ReassessmentTriggerTargetType` added for GC-T004 / C8 in issue #863,
  and the NIST SP 800-30 Rev. 1 classifiers `ThreatEventKind`,
  `ThreatSourceRelevance`, `NistLikelihoodBand`, and `NistImpactBand` added
  for GC-T014 / #721. `ThreatSourceRelevance` is a compatibility name for
  the Table E-4 threat-event relevance values. These are L0 data; the
  placement under `state/` follows convention only.

When adding a new file under `state/`, classify it explicitly in the file
header or the introducing PR's plan. The policy rule that requires this
section to be touched alongside new state files is a forcing function; the
real classification is the one above.
