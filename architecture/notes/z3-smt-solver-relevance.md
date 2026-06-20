# Z3 / SMT Solver Relevance to Ground Control

## The Question

As Ground Control expands from requirements management into design verification, implementation tracing, risk assessment, threat modeling, compliance mapping, and architectural governance - does a formal constraint solver like Z3 become relevant?

## What Z3 Actually Does

Z3 is a Satisfiability Modulo Theories (SMT) solver. Given a set of logical formulas over typed variables (booleans, integers, reals, bitvectors, arrays, uninterpreted functions), it determines whether the formulas are simultaneously satisfiable, and if so, provides a satisfying assignment. If unsatisfiable, it can produce an unsatisfiability proof or minimal conflicting subset.

In plain terms: you express constraints formally, Z3 tells you whether they can all be true at the same time, and if not, which ones conflict.

## Where Ground Control's Three Analysis Layers Sit

Ground Control is building three layers of analysis capability:

1. **Structural analysis** (exists today) - graph algorithms over the requirement DAG. Detects topological problems: cycles, orphans, coverage gaps, cross-wave violations. Operates on shape, not meaning.

2. **Semantic analysis** (GC-C015 through GC-C019) - embeddings and LLM classification over natural language content. Detects content-level problems: near-duplicates, contradictions, redundancy. Operates on meaning, but probabilistically.

3. **Formal analysis** (Z3 would live here) - constraint solving over formalized properties. Proves or disproves consistency, coverage, feasibility. Operates on logic, deterministically.

These three layers are complementary, not competing. Structural analysis is cheap and catches shape problems. Semantic analysis is moderate-cost and catches meaning problems. Formal analysis is expensive (in formalization effort, not compute) and catches logical problems with mathematical certainty.

## Where Z3 Adds Value That Other Approaches Don't

### 1. Requirement Constraint Consistency

When requirements carry formalizable numeric or logical constraints, Z3 can prove whether the combined constraint set is satisfiable.

**Example that embeddings can't catch:**
- GC-R001: "Session timeout shall not exceed 15 minutes"
- GC-R047: "Admin sessions shall persist for the duration of an active investigation, which may span multiple days"
- GC-R089: "All sessions shall use the same timeout policy"

Each requirement is individually coherent. Embedding similarity might flag R001/R047 as somewhat similar (both mention sessions), but might not - the language is quite different. An LLM might catch the contradiction in a pairwise check, but only if R001 and R047 are selected as a candidate pair. Z3, given the formalized constraints `timeout <= 15min`, `timeout >= days`, and `uniform_policy = true`, immediately produces UNSAT with the conflicting subset.

**The key difference:** Z3 doesn't need the requirements to be textually similar to detect they conflict. It reasons over the *implications* of constraints, not the *words* describing them.

### 2. Control-Threat Coverage Verification

GC-H007 (Threat Model Scope) and the control framework (GC-I002, GC-I007) create a mapping between threats and controls. The question "do our controls cover all identified threats?" is a set-cover problem that Z3 handles naturally.

**More interesting:** "Given that Control-A has failed its last effectiveness test, which threats are now unmitigated?" This is a satisfiability query: assert all control effectiveness constraints, negate Control-A's, ask Z3 which threat-mitigation constraints become unsatisfiable. The answer is the set of newly exposed threats - not just the threats Control-A directly covers, but threats that were transitively covered through control dependencies.

Graph analysis can trace these paths, but Z3 handles the case where coverage depends on *combinations* of controls (defense-in-depth), not just individual control-to-threat links.

### 3. Compliance Feasibility

GC-I002 (Compliance Framework Mapping) and GC-I007 (Framework Coverage Gap Analysis) map requirements/controls to framework elements. When an organization targets multiple frameworks simultaneously (SOC 2 + ISO 27001 + HIPAA), the question "can we satisfy all three frameworks with our current control inventory?" becomes a constraint satisfaction problem.

Z3 can determine:
- Whether the combined framework requirements are satisfiable with current controls
- The minimum additional controls needed to achieve full coverage
- Which framework requirements conflict (for example, data retention periods that differ between HIPAA and GDPR)

### 4. Wave Planning Feasibility

GC-Q007 (DAG-Derived Work Order) already envisions dependency-aware work ordering. Today this is topological sort with heuristics. But as constraints accumulate - dependency ordering, resource limits, deadline constraints, priority requirements - the planning problem becomes a constraint satisfaction problem.

"Given these dependency edges, these resource constraints (2 engineers), these deadlines (Wave 2 must complete by date X), and these priority rules (all MUST requirements before any SHOULD) - is this wave plan feasible? If not, what's the minimal relaxation?"

Z3 handles this directly. Heuristic planners approximate it.

### 5. Risk Appetite Consistency

GC-T005 (Risk Appetite & Tolerance) defines quantitative risk thresholds per category. GC-T006 (Risk Assessment Workflow) produces risk scores. The question "is our stated risk appetite internally consistent?" is a satisfiability question.

**Example:** An organization declares:
- Operational risk tolerance: residual score ≤ 8
- Availability risk tolerance: residual score ≤ 5
- "All systems shall maintain 99.99% uptime" (implies very low availability risk budget)
- "Cost of redundancy infrastructure shall not exceed $X" (limits the controls available)

These may or may not be simultaneously achievable. Z3 can tell you before you discover the conflict during an audit.

### 6. Architecture Constraint Verification

GC-J006 (Architecture Enforcement Coverage Analysis) tracks whether architectural decisions are enforced. Today this is binary (enforced/unenforced). But architectural constraints interact:

- "Domain layer shall not depend on infrastructure"
- "All external calls shall go through a gateway"
- "Gateway shall reside in infrastructure layer"
- "Domain services shall be able to make external calls"

These four constraints are collectively unsatisfiable. ArchUnit catches the direct dependency violation, but Z3 can catch the higher-order inconsistency in the *architecture decisions themselves* before anyone writes code.

## Where Z3 Does NOT Add Value

### Natural language requirements as-is

Z3 cannot process "The system shall provide a good user experience." Most requirements live at this level of formality. The formalization burden - translating natural language into SMT formulas - is the bottleneck, not the solving.

### Small-scale constraint sets

If a project has 5 requirements with numeric constraints, a human catches the conflicts. Z3's value scales with the number of interacting constraints where human reasoning fails.

### Probabilistic or subjective judgments

Risk scores, control effectiveness ratings, and priority assignments are judgment calls. Z3 reasons about hard constraints, not soft preferences. (Though Z3 can be used for optimization over soft constraints via MAX-SMT, this is a stretch.)

## The Formalization Problem

The fundamental challenge: Z3 requires formal specifications. Ground Control's requirements are natural language. Bridging this gap has three possible approaches:

### A. Manual formalization (traditional)

Humans annotate requirements with formal properties. GC-F007 (Target Assurance Level) and GC-F008 (Formal Specification Lifecycle Management) already envision this - requirements at L2/L3 assurance levels would have linked TLA+, Alloy, or JML specifications. These formal specs could feed Z3.

**Pro:** High-quality formalizations. **Con:** Expensive, only happens for high-assurance requirements.

### B. LLM-assisted formalization (emerging)

Use an LLM to extract formalizable constraints from natural language requirements. "Session timeout shall not exceed 15 minutes" → `timeout <= 900`. The LLM proposes, a human validates, Z3 consumes.

**Pro:** Scalable formalization with human-in-the-loop. **Con:** LLM extraction is imperfect - missed constraints or incorrect translations produce false confidence. Requires a validation step that adds friction.

### C. Structured requirement fields (pragmatic)

Extend the requirement model with optional structured constraint fields (numeric bounds, temporal constraints, logical preconditions) that authors fill in alongside natural language. The system extracts these directly into Z3 formulas.

**Pro:** No LLM involved, deterministic. **Con:** Additional authoring burden, only works for requirements with clearly formalizable properties.

Approach B is the most interesting for Ground Control's positioning - it leverages the same LLM infrastructure as GC-C018 (Semantic Coherence Classification) for a different purpose. The LLM extracts constraints; Z3 proves their consistency. Semantic analysis and formal analysis become two faces of the same NLP pipeline.

## Relationship to the Existing Formal Methods Stack

Ground Control already references formal methods:
- ADR-012 defines assurance levels L0-L3 with increasing formalization
- GC-F008 manages TLA+ specs, Alloy models, Dafny specs, JML contracts
- The coding standards reference property-based testing and formal verification

Z3 sits naturally at L2-L3. But the interesting application isn't "Z3 verifies code against specs" (Dafny and JML already do that). It's "Z3 verifies that the *requirements themselves* are consistent before anyone writes specs or code." This is earlier in the lifecycle and catches problems that are more expensive to fix later.

## Practical Sequencing

Z3 relevance increases as Ground Control accumulates formalized constraints. Right now, with purely natural language requirements and graph-based analysis, Z3 has no input to work with.

**Phase 1 (current):** Structural analysis. No formalization needed.
**Phase 2 (GC-C015-C019):** Semantic analysis via embeddings + LLM. Still natural language, but the LLM infrastructure is in place.
**Phase 3 (GC-F007/F008):** Formal specifications start appearing as linked artifacts. Z3 could verify spec-level consistency.
**Phase 4 (GRC expansion):** Controls, risks, threats, and compliance mappings introduce quantitative constraints. Z3 becomes relevant for coverage proofs and feasibility checking.
**Phase 5 (LLM-assisted formalization):** The LLM pipeline from Phase 2 is extended to extract constraints from natural language. Z3 consumes these. The full pipeline becomes: embeddings detect similarity → LLM classifies relationships → LLM extracts constraints → Z3 proves consistency.

Z3 doesn't become relevant tomorrow. It becomes relevant when the system has enough formalized constraints - from structured fields, from linked specifications, or from LLM extraction - to make satisfiability checking useful. The GRC expansion accelerates this because risk scores, control mappings, and compliance thresholds are inherently more formalizable than "the system shall provide a good user experience."

## The Grand Scheme

In the long view, Ground Control is building a stack:

```
Layer 4: Formal verification    ← Z3, proving constraint consistency
Layer 3: Semantic analysis      ← Embeddings + LLM, detecting content problems
Layer 2: Structural analysis    ← Graph algorithms, detecting shape problems
Layer 1: Data model             ← Requirements, relations, traceability, GRC entities
```

Each layer catches problems invisible to the layers below it. Structural analysis can't catch semantic overlap. Semantic analysis can't prove logical consistency. Z3 can't detect "these say similar things" - it needs formal input. The layers are complementary.

The answer to "does Z3 become relevant?" is: yes, but not until Layers 1-3 are solid and the system has enough formalized constraints to make satisfiability checking worthwhile. The GRC expansion (Waves 4-5) is likely where the crossover happens, because quantitative risk/compliance/control properties are naturally formalizable in ways that functional requirements often aren't.
