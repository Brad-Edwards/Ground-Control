# Context-Graph Ontology, Identity, and Time: Assessment

Status: assessment for issue #1301 (milestone 19). Decision record: ADR-084.
Evidence: full code census of this repository plus a read-only review of
`../aces-sdl` at `origin/dev`.

## 1. Census: what the vocabulary is today

### Nodes

`GraphEntityType` declares 26 node kinds. 23 are emitted by 16
`GraphProjectionContributor` implementations; three are dead or reserved
vocabulary never emitted anywhere: `CONTROL_LINK`, `AUDIT_LINK`,
`RISK_APPETITE_PROFILE` (the first two are reified-link concepts that are
actually projected as edges; the third has no contributor at all).

Roughly 20 domain packages have no graph presence. Some are correctly
absent (read-side composition layers such as `grcanalysis`, `evidencestate`,
`trace`; infrastructure such as `exception`). Others are thesis-relevant
absences: `derivation` (boundary model snapshots), `workflowtelemetry`
(workflow runs), `testcases`, `qualitygates`, `baselines`, `adrs`.

### Edges

Two edge families exist with no common governance:

- **Nine link/relation enums**, one per aggregate, whose `.name()` becomes
  the edge string: requirements `RelationType` (6 values), assets
  `AssetRelationType` (7) and `AssetLinkType` (7), controls
  `ControlLinkType` (7), findings `FindingLinkType` (7), risk scenarios
  `RiskScenarioLinkType` (9), threat models `ThreatModelLinkType` (7),
  audits `AuditLinkType` (5), research `ResearchArtifactType` (8). Each has
  a companion `*LinkTargetType` enum bounding endpoints.
- **~25 literal edge strings hard-coded inside contributors** (`ASSESSES`,
  `TRACKS`, `TREATS`, `MAPS_CONTROL`, `OF_CONTROL`, `HAS_SOURCE`,
  `SUPERSEDED_BY`, `OBSERVED_ON`, `DATA_FLOW`, ...), invisible to any enum
  or check.

The drift is not hypothetical. The same concept is minted independently
per aggregate with no declaration that the meanings coincide:
`ASSOCIATED` appears in six vocabularies, `EVIDENCED_BY` in five,
`OBSERVED_IN` in four, `DEPENDS_ON` and `IMPLEMENTS` and `AFFECTS` and
`MITIGATED_BY` in multiple each. Nothing states whether
`ControlLinkType.EVIDENCED_BY` and `FindingLinkType.EVIDENCED_BY` are one
concept or two. This is exactly the failure mode the ACES concept-authority
work names: artifact-local strings becoming de facto semantics.

### The sharpest finding: traceability is not in the graph

`TraceabilityLink` - the `IMPLEMENTS` / `TESTS` / `DOCUMENTS` /
`CONSTRAINS` / `VERIFIES` edges from requirements to artifacts, the
platform's founding value proposition - is **not projected into the AGE
graph**. Only requirement-to-requirement `RelationType` edges are. The
"living context graph" currently omits the traceability spine; coverage
questions are answered relationally, invisible to graph traversal, and the
GRC screening's code-keyed link walks run on a different plane than the
graph the console renders.

### Identity

Uniform and healthy: node id = `GraphEntityType.name() + ":" + UUID`
(`GraphIds.nodeId`), with the human UID (`GC-XXX-NNN`) carried as a
property where one exists, and project scoping validated at query time.
No changes needed beyond registering the scheme in the ontology artifact.

### Time

Richer than expected, but fragmented across five mechanisms with no
declared spine:

| Mechanism | Scope | As-of capable? |
|-----------|-------|----------------|
| Envers audit (~80 entities, custom revision entity with actor) | Nearly every aggregate | Yes - true point-in-time reconstruction exists (`BaselineService.getRequirementsAtRevision`, `AuditService` diffs) but only requirements/baselines use it |
| ADR-045 evidence state history | Observations vs evidence artifacts; append-only supersession chains | Partially - `asOf` params on evidence-state and GRC analysis reads |
| ADR-062 graph snapshots | Whole-projection versions with publication metadata | Snapshot-granular only; no tie to Envers revisions |
| Baselines | Requirements only, pinned to an Envers revision | Yes, requirements only |
| Ad hoc `asOf` params | Many GRC read services | Per-service semantics, undeclared |

The asset that matters: Envers revision numbers are already a total order
over nearly all domain writes, with actor attribution. A canonical as-of
spine can be declared rather than built.

### Ontology documentation

Prose only (`docs/architecture/ARCHITECTURE.md`, Mixed-Entity Graph
section) plus ADRs 005/011/019/020/032/045/062/070. There is no
machine-readable ontology artifact; the authoritative vocabulary lives in
one enum, nine link enums, and contributor literals.

## 2. The ACES SDL question

### What actually exists at `../aces-sdl` origin/dev

A YAML-based, backend-agnostic scenario description language with a Python
reference implementation - and, more importantly for this assessment, a
**production-grade concept-authority stack** (its ADR-012; GOV-917 through
GOV-922, all implemented):

- Three layers: concept authority (what concepts mean; UCO as the cyber
  semantic spine) / ACES-native concepts / **artifact bindings** (each
  artifact binds its local strings to canonical concepts, preventing
  artifact-local semantics).
- **Concept families as data** (`contracts/concept-authority/concept-families-v1.json`),
  each carrying `provenance` (adopted / adapted / native) and, for native
  families, mandatory `extension_scope`, `relation_rules`, and
  `non_ambiguity_constraints`.
- **A closed effect vocabulary for external ontologies**: an external
  authority may only `annotate | align | refine | constrain` native
  meaning - never become syntax or implicit schema inheritance.
- Controlled vocabularies with governed extension (`x-<owner>:<term>`),
  shared semantic profiles, reference models, and a content-hashed schema
  publication manifest with an explicit evolution policy.
- Its governing rule, verbatim in spirit: work extends the existing
  semantic authority stack rather than introducing a parallel registry.
- A typed declared-vs-observed reconciliation model: `CREATE / UPDATE /
  DELETE / UNCHANGED` deltas computed against provenance-bound snapshots,
  and a realization-provenance ledger classifying every realized concern as
  author-declared, processor-derived, or backend-realized.

### What does not exist there

- It is **cyber-range domain-specialized**: the concept families are
  assets, identities, observables, actions, scenarios, episodes, runtime
  inventory. There are no families for requirements, GRC controls,
  traceability, evidence campaigns, or engineering process. Its "evidence"
  is experiment evidence; its "controls" are runtime security posture.
- It is JSON-Schema/Pydantic, not the W3C stack - a governance discipline,
  not an OWL ontology.
- Its semantic-integrity umbrella requirement (SEM-200) is still DRAFT
  with an honestly tracked active/partial/planned coverage table, and no
  production backend exists.

### The overlap that matters

The overlap is at the **metamodel level**, and it is large:

| Shared problem | ACES artifact | Ground Control counterpart |
|----------------|---------------|----------------------------|
| Bounded, owned semantic scopes | The "surface" primitive (named, bounded, contract-bearing, singly owned) | Boundary / architecture-model element / CLD lock levels |
| Vocabulary drift across artifacts | Concept families + artifact bindings | Nine link enums + literal edge strings (the drift is live here) |
| Declared vs observed state | Snapshot reconciliation deltas + realization provenance | Derivation/drift machinery, GC-GRC-009 impact/gap/stale sets |
| Evidence vs interpretation | Captured evidence vs derived measures (EXP-708/709) | ADR-045 observations vs evidence artifacts |
| Proportionate assurance | Classification-based assurance policy (ASR-505) | ADR-012 ladder, CLD risk scoring |
| Human/agent mixed control | Supervision/intervention/handoff lifecycles (ACT-617, RUN-310) | HITL gate model (ADR-029, GC-O009) |
| Provenance | Provenance-and-evidence family, alignment provenance | Research provenance ledger (ADR-069), derivation provenance |

The user's framing holds: a cyber range is an IT organization at the
limit, and a software organization's development process is an
environment with participants, objectives, workflows, evidence, and
drift. The two products are modeling the same metamodel from two domains.

## 3. Options

**A. Ignore ACES; consolidate GC-natively with a bespoke shape.** Fixes
the drift but invents a second, incompatible semantic-governance pattern
inside one portfolio - the exact "parallel registry" anti-pattern ACES's
discipline exists to forbid. Rejected.

**B. Adopt ACES SDL as Ground Control's ontology substrate now.** Rejected
on maturity and fit: SEM-200 is DRAFT, the domain families GC needs do not
exist there, and coupling GC's shipping ontology to another pre-1.0
product's roadmap violates the prefer-no-regression rule. GC would also
distort ACES: cramming requirements/GRC/traceability into a cyber-range
vocabulary serves neither.

**C. Adopt the ACES concept-authority *pattern* now, with GC-native
families in the same artifact shape, a versioned crosswalk to ACES
families under the closed effect vocabulary, and companion-spec extraction
as the stated trajectory.** GC publishes its ontology as machine-readable
data (families with provenance and extension rules; controlled edge
vocabularies with owners; artifact bindings) under the ADR-082 contracts
surface, gated by inventory-driven policy in the ADR-034 style. Where a GC
family genuinely shares a concept with an ACES family (assets,
observables/evidence, provenance, time, tasks-runs-studies), a crosswalk
artifact records the alignment with `aligns`/`refines` semantics - no
runtime dependency in either direction. When ACES's SEM-200 reaches ACTIVE
and both sides have working family sets, the shared metamodel (surface,
family, binding, provenance classes, reconciliation deltas, evidence vs
derived measures, time) is extracted into a companion spec both consume as
domain packs - and because both sides used the same artifact shape from
the start, that extraction is a merge, not a rewrite.

**Recommendation: C.** It takes the proven thing that exists (the
discipline, which is also portfolio-native), refuses the premature thing
(runtime coupling to a DRAFT semantic core), and makes the eventual
convergence mechanical instead of aspirational.

## 4. Repairs the census demands regardless of the ACES decision

1. Project `TraceabilityLink` edges into the graph. The traceability spine
   belongs in the context graph; its absence undercuts the thesis directly.
2. Retire or implement the three dead node kinds; dead vocabulary in a
   governed ontology is a contradiction.
3. Consolidate edge semantics: shared concepts (`EVIDENCED_BY`,
   `ASSOCIATED`, `OBSERVED_IN`, ...) declared once in a controlled
   vocabulary and bound per aggregate, replacing nine independent mints
   and 25 unregistered literals.
4. Declare the time spine: Envers revision as the canonical as-of
   coordinate; snapshot metadata (ADR-062) records the revision it was
   built from; `asOf` parameters get one defined semantics.
5. Decide the thesis-relevant missing projections (workflow runs, work
   items, derivation/boundary snapshots, contracts and invariants when
   milestone 18 lands them).

## 5. Decision and work layout

The binding decision is ADR-084 (option C plus the repairs). Follow-on
implementation issues are filed in milestone 19; see the milestone for the
current set.
