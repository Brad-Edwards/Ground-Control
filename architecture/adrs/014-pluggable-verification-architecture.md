# ADR-014: Pluggable Verification Architecture

## Status

Accepted

## Date

2026-03-09

## Context

Ground Control's vision is a verification-aware software lifecycle orchestrator with graph-native artifact traceability. Two landscape analyses ([compass reports](../notes/)) confirm that no platform combines requirements management, formal specifications, threat modeling, code generation, and graph-based artifact traceability—and that the **graph-based artifact layer** is the genuinely novel element.

ADR-013 chose Java 21/Spring Boot and established JML/OpenJML/KeY as the formal methods chain for Ground Control's own code. This chain works for dogfooding—the platform's internal code quality. However, the industrial secure software development use case requires the platform to orchestrate verification of software written in any language, not just Java.

### The polyglot verification problem

Security-critical software spans multiple languages and abstraction levels:

| Target | Domain | Verifiers |
|--------|--------|-----------|
| Java services | Enterprise backends | OpenJML ESC, KeY |
| C/C++ | Automotive, aerospace, embedded, firmware | Frama-C, CBMC, TrustInSoft |
| Rust | Systems, security-critical infrastructure | Verus, Kani |
| Go | Cloud native, infrastructure | Dafny (via translation) |
| Smart contracts | Blockchain, DeFi | Certora CVL, Solidity SMTChecker |
| Infrastructure-as-Code | Cloud configuration | OPA/Rego, Checkov |
| Policy-as-Code | Authorization, compliance | OPA/Rego, Kyverno, Cedar |
| System designs | Distributed protocols, state machines | TLA+/TLC, Alloy |

No single prover covers this surface. The successful industrial deployments use the right tool for the right abstraction level:

- **AWS**: TLA+ for design-level verification of distributed protocols, CBMC for C code bounded model checking, Zelkova (custom SMT) for IAM policy verification, Tiros for network reachability. Engineers learn TLA+ in 2-3 weeks.
- **Meta**: Infer (separation logic) runs on every code modification. Over 1,000 bugs caught per month. Developers don't know they're using formal methods.
- **Certora**: AI-generated smart contract code must pass CVL formal verification before deployment. The forcing function is economic: $3.8 billion stolen from unverified contracts in 2022.

### Design-level vs. code-level verification

The AWS experience shows that design-level verification delivers the highest ROI. TLA+ finds "subtle bugs we are sure we would not have found by other means"—and it operates before code exists.

Ground Control's core complexity maps directly to design-level properties:

- State machine completeness (requirements lifecycle, no deadlocks)
- DAG invariant preservation (concurrent relation creation cannot introduce cycles)
- AGE materialization consistency (graph projection converges to ORM truth)
- Traceability completeness (impact analysis reaches all affected artifacts)
- Multi-prover orchestration correctness (verification pipeline itself)

These are exactly the properties TLA+ and Alloy are designed to verify. Investing here is higher-value than expanding OpenJML ESC coverage from 4 enums to 8.

### The strategic positioning gap

The compass reports identify a critical gap in the market:

- **Spec-driven dev platforms** (AWS Kiro, GitHub Spec Kit, Tessl) enforce process without mathematical guarantees—natural language specs only.
- **Formal-reasoning startups** (Harmonic, Logical Intelligence, Axiom Math) build verification engines without developer-facing platforms.
- **ALM tools** (IBM ELM, Polarion, Jama) handle requirements without formal methods.
- **Formal methods tools** (SCADE, SPARK, Frama-C) verify code without lifecycle awareness.

The whitespace is a platform that connects all of these through a queryable artifact graph. Coupling that graph to a single prover (JML/KeY) would limit the platform to Java and miss the industrial market.

## Decision

### 1. Verification pipeline as orchestration layer

Ground Control's verification pipeline is an **orchestration abstraction**, not a specific prover. The platform invokes the appropriate verifier for the target language and abstraction level, then stores results in the graph as first-class artifacts.

```
                        Graph (AGE)
                            ^
                            | VerificationResult
             +--------------+---------------+
             |              |               |
       +-----+------+ +----+-----+  +------+------+
       |  Design     | |  Code    |  |  Policy     |
       |  Verifiers  | |  Verifiers|  |  Verifiers  |
       +-------------+ +----------+  +-------------+
       | TLA+ / TLC  | | OpenJML  |  | OPA / Rego  |
       | Alloy       | | Frama-C  |  | Kyverno     |
       |             | | Verus    |  | Cedar       |
       |             | | Dafny    |  |             |
       |             | | KeY      |  |             |
       |             | | CBMC     |  |             |
       |             | | Kani     |  |             |
       +-------------+ +----------+  +-------------+
```

Each verifier backend is a thin adapter. Ground Control orchestrates—it does not maintain the provers.

### 2. Prover-agnostic verification results

A `VerificationResult` domain entity captures results from any verifier in a common schema:

| Field | Type | Description |
|-------|------|-------------|
| id | UUID | Primary key |
| target | FK -> TraceabilityLink | What was verified (code file, spec, config, design model) |
| requirement | FK -> Requirement (nullable) | The requirement driving this verification |
| prover | String | Tool identifier: `openjml-esc`, `key`, `tlaplus-tlc`, `alloy`, `frama-c`, `verus`, `dafny`, `opa`, `cbmc`, `manual-review` |
| property | TEXT | The formal property checked (human-readable description + formal notation) |
| result | Enum | `PROVEN`, `REFUTED`, `TIMEOUT`, `UNKNOWN`, `ERROR` |
| assuranceLevel | Enum | `L0`, `L1`, `L2`, `L3` |
| evidence | JSONB | Prover-specific output: proof artifact path, counterexample, log, duration |
| verifiedAt | Instant | When the verification ran |
| expiresAt | Instant (nullable) | When this result should be re-verified (code changed, dependency updated) |

The graph stores VerificationResult nodes connected to Requirement nodes and TraceabilityLink nodes. A single Cypher query can answer: "Show me all security requirements where no verification result has achieved L2 in the last 30 days."

### 3. Assurance levels as universal framework

ADR-012's assurance levels (L0-L3) are a **methodology**, not a tool binding. The level definitions are universal; the tools at each level depend on the target language and abstraction:

| Level | Methodology (universal) | Ground Control's Own Code | Platform Output (polyglot) |
|-------|------------------------|---------------------------|---------------------------|
| L0 | Tested | `javac` + JUnit 5 | Language-native compiler + tests |
| L1 | Contracted | JML `requires`/`ensures` + JUnit 5 | Language-appropriate contracts: JML, ACSL, Verus annotations, type contracts, `requires` clauses |
| L2 | Design/Property-Verified | jqwik + TLA+ design specs | TLA+/Alloy for design; jqwik, proptest, Hypothesis, QuickCheck for properties; OpenJML ESC where applicable |
| L3 | Formally Proven | KeY (future, for own code) | Dafny, Lean 4, KeY, Frama-C/WP, Verus—matched to target language |

### 4. TLA+ for design-level verification

TLA+ is adopted for verifying Ground Control's own design-level properties:

| Property | Scope | What TLC Checks |
|----------|-------|-----------------|
| State machine completeness | Requirements lifecycle | All states reachable, no deadlocks, transition table covers all enum values |
| DAG invariant preservation | Requirement relations | Concurrent `createRelation` calls cannot introduce cycles |
| Materialization consistency | AGE graph sync | After sync completes, graph is a faithful projection of ORM state |
| Traceability completeness | Impact analysis | If requirement R changes, traversal reaches all dependent artifacts |
| Verification pipeline | Orchestration | No verification result is stored without prover confirmation |

TLA+ specs live in `specs/tla/` at the repository root, versioned with code. TLC model checking runs as part of the verification pipeline.

Implementation is staged. The repository now carries versioned TLA+ specs and
policy sync tooling that keeps verification-oriented ADR and quality-gate
metadata aligned. The policy-synced quality-gate set
(`tools/ground_control/policy.json`) enforces implementation, test, and
documentation traceability coverage plus orphan and completeness checks; it is
evaluated in CI (`make policy-live`) and at the `/implement` completion gate
via `gc_assert_quality_gates`. Live ADR metadata checks read the ADR title from
the API's `folder_title` response field, matching the sync payload used when
repo ADR titles drift. The common `VerificationResult` domain entity is
implemented; verifier execution/orchestration adapters remain future work.

### 5. Separation of concerns

| Concern | Scope | Tools |
|---------|-------|-------|
| Ground Control's own code quality | Internal dogfooding | JML L1 contracts + jqwik + JUnit 5 + TLA+ design specs |
| Platform verification capabilities | User-facing feature | Pluggable verifier backends, orchestration, graph-stored results |
| Verification UX | Future | Natural language -> formal spec translation (likely Dafny-based, per MSR prototype pattern) |

Ground Control uses formal methods internally (dogfooding) AND provides formal methods orchestration as a platform capability. These are separate concerns with separate tooling.

### 6. Domain structure for verification

```
domain/
    requirements/           # Existing (ADR-011)
    verification/           # New domain area
        model/
            VerificationResult.java
            VerificationStatus.java   # PROVEN, REFUTED, TIMEOUT, UNKNOWN, ERROR
            AssuranceLevel.java       # L0-L3
        service/
            VerificationResultService.java  # Stores results, resolves target/requirement, queries status
            VerifierAdapter.java            # Port: execute a verifier and return a common result payload
        repository/
            VerificationResultRepository.java

infrastructure/
    verifiers/              # Adapter layer for external provers
        OpenJmlAdapter.java          # Calls OpenJML ESC on Java source
        TlcAdapter.java              # Calls TLC on TLA+ specs
        OpaAdapter.java              # Calls OPA on Rego policies
        # Future: FramaCAdapter, VerusAdapter, DafnyAdapter, KeyAdapter
```

The `infrastructure/verifiers/` package follows the ports-and-adapters pattern: `domain/` defines what a verification result looks like; `infrastructure/` knows how to invoke specific provers.

Adapter guardrails:

- The canonical verifier selector is the existing `VerificationResult.prover` string. Do not introduce a `VerifierType` enum or a second registry schema that can drift from persisted data and API/MCP contracts.
- Adapter implementations are execution boundaries only. They must not depend on controllers or repositories, and they must not persist `VerificationResult` entities directly. Project resolution, target/requirement resolution, and result persistence stay in the domain service layer.
- Tool-specific output belongs in the existing `evidence` JSONB/TEXT map so the common schema stays stable. Do not add per-verifier tables, nullable columns, or parallel DTO hierarchies unless a shared query or workflow requirement proves the need.
- Manual review participates through the same adapter contract and common result schema. It is not a special-case bypass around validation, auditing, or persistence.
- External verifier integrations must follow existing infrastructure patterns: validated configuration, bounded execution, structured logs, and no shell-string command construction or secret-rich log output.

## Consequences

### Positive

- Platform can verify software in any language—not limited to Java
- Graph-based traceability works across verification tools: the differentiator is preserved and strengthened
- Reverse traceability lookup (`GET /requirements/traceability/by-artifact`) enables automated enforcement that code is linked to requirements, closing the bidirectional traceability loop and supporting self-referential validation (GC-O002). Lookup errors are tracked separately from untraced files for operational debuggability.
- Security use case is viable: threat model -> security requirement -> formal property -> language-specific proof -> graph-stored evidence
- Design-level verification via TLA+ is immediately actionable, high-ROI, and proven at AWS scale
- No rearchitecting needed when adding new prover backends
- Compliance evidence generation (DO-178C, ISO 26262, IEC 62304) works regardless of which prover produced the evidence—the graph connects requirements to verification results uniformly
- The Infer pattern (invisible formal methods in CI) is achievable: users push code, the platform runs appropriate verifiers, results appear in the graph

### Negative

- More abstraction upfront than a JML-only pipeline
- Each new prover backend requires an adapter implementation
- TLA+ is a new tool to learn (mitigated: 2-3 week learning curve per AWS experience; high ROI for design-level bugs)
- The platform must maintain adapter compatibility as provers evolve

### Risks

| Risk | Mitigation |
|------|------------|
| Orchestration layer adds complexity without enough backends to justify it | Start with 2 backends (OpenJML for Java dogfooding, TLA+/TLC for design specs). Add backends as the platform's verification features are built out. |
| TLA+ specs drift from implementation | Specs are versioned with code. Spec-to-implementation review is part of the SDD workflow. TLC runs in CI. |
| Prover-agnostic result schema loses prover-specific nuance | The `evidence` JSONB field stores raw prover output. The schema captures universal properties (proven/refuted/timeout); JSONB preserves prover-specific details (counterexamples, proof terms, coverage metrics). |
| Too many verification tools to maintain | Ground Control orchestrates—it doesn't maintain the provers themselves. Each backend is a thin adapter (~100-200 lines) calling an external tool's CLI or API. |
| VerificationResult expiry/staleness is hard to track | `expiresAt` field combined with TraceabilityLink's `syncStatus` flag stale results when source artifacts change. Graph queries surface "verification gaps"—requirements with expired or missing results. |

## Implementation Status

- **§2 VerificationResult entity**—Implemented (GC-F001). Domain: `domain/verification/`, API: `api/verification/`, Migrations: V049-V050, MCP: 5 tools. Target and requirement FKs use eager fetch.
- **§6 infrastructure/verifiers/ adapters**—Not yet implemented. Future work per individual prover requirements.

## Related ADRs

- **ADR-005** (Apache AGE)—The graph is the integration point for multi-prover verification results. Cypher queries span requirements, traceability links, and verification results.
- **ADR-011** (Requirements Data Model)—TraceabilityLink connects requirements to verification targets. VerificationResult connects to both.
- **ADR-012** (Formal Methods Development Process)—SDD methodology and assurance levels remain valid and are now explicitly universal. Tool bindings per level depend on target language.
- **ADR-013** (Java/Spring Boot Backend Rewrite)—Java/JML/OpenJML remain the choice for Ground Control's own code. This ADR separates internal dogfooding from platform capabilities.
