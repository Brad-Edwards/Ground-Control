# Mechanism Catalog

Per-layer enforcement mechanisms, mapped to this portfolio's stacks
(Java 21 / Spring Boot; TypeScript / React; Node MCP; polyglot consumers).
"Now" means the mechanism exists or is one issue away; "later" means it
depends on the ADR (#1291) or on milestone-17 substrate.

## Layer 1: Structural (architecture as code)

| Mechanism | Stack | Status |
|-----------|-------|--------|
| Architecture registry: module graph, allowed edges, ownership, lock levels, mutation thresholds as one data file | any | Later (#1295) |
| ArchUnit rules asserted against the registry (today: hand-listed layering rules) | Java | Now (rules exist), Later (registry-driven) |
| depguard-style import denial, sensitive-SDK confinement ("LLM SDKs only in the provider module") | Go reference: golangci-lint depguard; Java: ArchUnit + Gradle module boundaries; TS: eslint boundaries / import rules | Now (Java), Later (TS/MCP) |
| CODEOWNERS routing for registry and contract paths | any | Now |

## Layer 2: Syntactic (interface shape)

| Mechanism | Stack | Status |
|-----------|-------|--------|
| Committed generated OpenAPI as artifact of record; regenerate-and-diff drift gate | Java (Springdoc) | Now (ADR-082, #1275) |
| Generated TypeScript client replacing hand mirrors | TS | Now (ADR-082, #1275) |
| JSON Schema (draft 2020-12) for durable records and workflow/activity payloads, versioned in-name | any | Now (ADR-082) |
| Breaking-change gate: oasdiff (OpenAPI), schema-version discipline; declared-breaking escape via CHANGES.md | any | Now (ADR-082, #1275) |
| Enum/DTO mirror gates (interim where generation has not replaced mirrors) | Java/TS/MCP | Now (ADR-034, GC-O013) |

## Layer 3: Semantic (behavior)

| Mechanism | Stack | Status |
|-----------|-------|--------|
| Abstract port conformance suites run against in-memory and JPA implementations | Java (JUnit) | Later (#1292 scaffolds; pattern decided in ADR-082) |
| Property-based tests on declared invariants | Java: jqwik (present); TS: fast-check | Now (Java), Later (TS) |
| Bean Validation + structured error envelope at every trust boundary | Java | Now |
| JML contracts + OpenJML ESC on scored state packages | Java | Now (ADR-012) |
| Invariant inventory: stable IDs mapped to enforcing checks, policy-gated | any | Later (#1275/#1292) |
| Negative-suite generation from contract data (authz matrix, malformed input, illegal transitions) | Java/TS | Later (#1292) |
| Executable reference models + differential testing with counterexample minimization | any | Later (#1292) |

## Layer 4: Protocol (ordering, concurrency, lifecycle)

| Mechanism | Stack | Status |
|-----------|-------|--------|
| Enum transition tables with exhaustive matrix tests (house pattern) | Java | Now |
| jqwik property tests over state DAGs | Java | Now (ADR-012 L2) |
| TLA+ specs model-checked in CI, with spec-action-to-code maps | design level | Now (toolchain per ADR-014; applied where scored) |
| Temporal workflow replay/determinism tests; crash-resume tests | Java (Temporal test env) | Later (#1277) |
| Alloy for relational/lattice invariants | design level | Later (as scored) |

## Layer 5: Policy (cross-cutting obligations)

| Mechanism | Stack | Status |
|-----------|-------|--------|
| Authorization path/role matrix as data + config-matches-data policy check | Java | Later (#1275, ADR-082) |
| Negative-authorization matrix tests per endpoint class | Java | Now (pattern), Later (mandatory generation) |
| Repo-native policy runner for static post-conditions (bin/policy inventory-driven checks) | any | Now (ADR-034 pattern) |
| Redaction rules with inspection tests (no secrets/prompts in histories, logs, metrics) | Java | Later (#1276-#1285 as built) |
| Protected-path gate: implementation diffs may not touch contracts/battery/registry/policy without a design-authority marker | any | Later (#1294) |
| Changelog-fragment-style packaged conventions for consumer repos | any | Now (pattern), Later (#1299 for CLD kit) |

## Meta-oracle and evaluation

| Mechanism | Stack | Status |
|-----------|-------|--------|
| Mutation testing with per-boundary thresholds | Java: PIT; TS: Stryker | Later (#1293) |
| Seeded-defect red-team runs scoring battery catch rate | any | Later (#1297) |
| Metrics: invariant coverage, first-pass green rate, defect escape rate | any | Later (#1297) |

## Selection guidance (until the ADR fixes it)

Apply the moonbase-style risk score per boundary (irreversibility x
concurrency x security criticality, or the ADR-012 decision table where it
already answers):

- Score high on any axis: layers 1-3 plus the axis-appropriate layer 4/5
  element, differential reference model if the boundary is a data-integrity
  or money/security path, mutation threshold at the strict tier.
- Mid: layers 1-3, property tests on the generative invariants, standard
  mutation threshold.
- Low: layers 1-2 only; interior freedom is the feature.
