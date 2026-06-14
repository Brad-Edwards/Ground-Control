# GC-T003 Risk Scenario-Control Mapping - Verification Record

Issue: #824
Requirement: GC-T003 (DRAFT, wave 4)
Branch verified: `824-verify-gc-t003-risk-scenario-control-mapping-clause-by-clause-audit-transition-draft→active-reconcile-traceability`
Base: `dev` at `7842270` (pre-verification HEAD; verification ran against the working tree of this branch, which carries this note and its companion preflight note as the only diff from base)
Companion: [`risk-control-mapping-preflight.md`](risk-control-mapping-preflight.md)

This note records the clause-by-clause verification of GC-T003 against the
current codebase, the GC-I013 hard dependency, and the gaps that drive
implementation issue #258. The preflight note records architecture
guardrails for the future implementation; this note records where the
requirement actually stands today.

## Verdict

**GC-T003 stays DRAFT.** Zero clauses SATISFIED, three PARTIAL (C1, C2,
C8), four UNSATISFIED (C3, C4, C5, C6), one BLOCKED-BY-DEPENDENCY (C7).
The dependency block is GC-I013 *Control Effectiveness Assessment*
(DRAFT, wave 4). The clause-by-clause table below is the authoritative
count; this summary line and the changelog fragment must match it.

| Clause | Verdict |
| ------ | ------- |
| C1 Bidirectional many-to-many mapping | PARTIAL |
| C2 Asset / operational-boundary context on the mapping | PARTIAL |
| C3 Recorded fields: objective, role, scope, methodology influence | UNSATISFIED |
| C4 Methodology-specific influence (qualitative + FAIR-aligned) | UNSATISFIED |
| C5 Identification of scenarios / records with no mapped controls | UNSATISFIED |
| C6 Identification of controls not mapped to any relevant scenario | UNSATISFIED |
| C7 GC-I013 evaluation results feed linked risk assessments | BLOCKED-BY-DEPENDENCY |
| C8 Observations / evidence feed linked risk assessments | PARTIAL |

## Requirement statement (for reference)

> The system shall support bidirectional many-to-many mapping between
> controls or scoped control implementations and risk scenarios or risk
> register records on relevant operational assets or boundaries. A
> mapping shall record the control objective, control role, scope, and
> methodology-specific influence on risk reduction, such as qualitative
> likelihood or consequence dimensions or FAIR-aligned frequency and
> magnitude factors. The system shall identify scenarios or records with
> no mapped controls and controls not mapped to any relevant scenario,
> and control evaluation results from GC-I013 plus relevant observations
> or evidence shall feed linked risk assessments.

## Clause-by-clause evidence

### C1 - Bidirectional many-to-many mapping. **PARTIAL.**

The requirement covers two cross-products: `controls OR scoped control
implementations` against `risk scenarios OR risk register records`. Of
those four combinations, only one - catalog-control ↔ scenario - has
two-sided link infrastructure today.

- **Catalog control ↔ scenario (two-sided).**
  Scenario→control: `RiskScenarioLink` with `target_type = CONTROL`.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/RiskScenarioLinkTargetType.java:6`
  Control→scenario: `ControlLink` with `target_type = RISK_SCENARIO`.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/state/ControlLinkTargetType.java:5`
  Reverse lookup on the scenario side is queryable by project and
  target entity id.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/repository/RiskScenarioLinkRepository.java:37-51`
  Many-to-many is enforced by the link tables' unique constraints.
  `backend/src/main/resources/db/migration/V041__create_risk_scenario_link.sql:11`,
  `backend/src/main/resources/db/migration/V046__create_control.sql:36-41`

- **Catalog control ↔ risk register record (one-sided).**
  Control→record: `ControlLink` with `target_type =
  RISK_REGISTER_RECORD`.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/state/ControlLinkTargetType.java:6`
  Record→control: **no link table on the record side.**
  `RiskRegisterRecord` has no `*Link` companion entity and no
  `RiskRegisterRecordLink*` files exist in
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/`.
  `ControlLinkRepository` has no reverse-lookup query keyed on
  `(target_type = RISK_REGISTER_RECORD, target_entity_id, project)`
  that a record-side service could call.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/repository/ControlLinkRepository.java:14-33`

- **Scoped control implementations (no first-class concept).** No
  entity in the codebase represents a per-deployment scoped control
  implementation as a distinct mapping endpoint. The candidates are:
  `Control.implementationScope` is free text on the catalog control.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/model/Control.java:56-57`
  `ControlPackEntry` exists but is the catalog-distribution overlay
  (control-pack import), not a per-deployment scoped implementation.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controlpacks/model/ControlPackEntry.java`
  `ControlLink` with `target_type` of `ASSET`, `CODE`, `CONFIGURATION`,
  or `OPERATIONAL_ARTIFACT` represents "this catalog control is
  implemented by that artifact," but those links anchor on the catalog
  `Control`, not on a separate scoped-implementation row that could
  itself be the mapping endpoint.

**Gap (consolidated).**

- `ControlLink` and `RiskScenarioLink` are independent tables. Nothing
  forces a mapping to exist symmetrically on both sides, and nothing
  prevents the metadata on the two sides from diverging once C3/C4
  fields are added. The preflight (§Gotchas) flags this: "Do not rely
  on both ControlLink and RiskScenarioLink independently and call that
  bidirectional mapping if their metadata or validation can diverge."
  There is no canonical owner of the mapping today.
- Record↔control bidirection is one-sided. A record-side query for
  "which controls mitigate this register record" is not supported by
  the current repository surface and would need either a new reverse
  query on `ControlLinkRepository` or a `RiskRegisterRecordLink` entity.
- The requirement language "controls or scoped control implementations"
  is not directly modeled. #258 must decide: (a) introduce a
  first-class `ScopedControlImplementation` entity that can be the
  mapping endpoint, (b) treat existing `ControlLink` artifact-target
  rows as the scoped-implementation projection and document that
  choice, or (c) scope GC-T003 to catalog-control endpoints only and
  note the deviation from the requirement statement.

### C2 - Asset / operational-boundary context. **PARTIAL.**

The asset concept is reachable, but not as a field on the mapping row
itself.

- `ASSET` is a valid `target_type` on both link entities.
  `ControlLinkTargetType.java:4`,
  `RiskScenarioLinkTargetType.java:15`
- `OperationalAsset` exists as a first-class entity and is the obvious
  target for `ASSET` links.

**Gap.** A mapping like "control X mitigates scenario Y in the context
of asset Z" is not expressible as a single row. It requires three
independent links: control→scenario, control→asset, scenario→asset.
Nothing in the schema, service, or repository enforces that the asset
context referenced by the control→scenario mapping matches the asset
context referenced by the scenario or by the control. The preflight
(§Boundary) requires "asset or boundary context" to be "represented
somewhere explicit" on the mapping; "explicit" requires either a
column on the mapping row or a structural invariant enforced by the
service.

### C3 - Objective, role, scope on the mapping. **UNSATISFIED.**

The link rows have no columns for these.

- `ControlLink` carries only `control`, `targetType`, `targetEntityId`,
  `targetIdentifier`, `linkType`, `targetUrl`, `targetTitle`.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/model/ControlLink.java:28-50`
- `RiskScenarioLink` carries only the symmetric set.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskScenarioLink.java:30-54`
- `control_link` and `risk_scenario_link` schemas confirm this at the
  DDL level.
  `V046__create_control.sql:25-44`, `V041__create_risk_scenario_link.sql:1-16`
- Catalog-level `Control.objective` and `Control.implementationScope`
  exist on the control itself, but those are not the mapping-specific
  fields the requirement asks for, and
  `Control.implementationScope` is unstructured free text.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/model/Control.java:42-43, 56-57`

**Gap.** The mapping-specific control objective (how this control
reduces risk *for this scenario or this record*), mapping-specific
role (this control acts as preventive for scenario X but detective
for record Y), and mapping-specific scope (the portion of the catalog
control that applies to this mapping endpoint) have no storage.
Adding these requires either columns on the existing link tables or a
dedicated mapping entity that the link tables defer to - and the
endpoint shape depends on C1's scoped-implementation decision.

### C4 - Methodology-specific influence. **UNSATISFIED.**

No storage exists on the mapping for FAIR frequency / magnitude
reduction factors or for qualitative likelihood / consequence reduction.

- Neither link entity has a field for influence factors.
  `ControlLink.java:28-50`, `RiskScenarioLink.java:30-54`
- `Control.methodologyFactors` is a `Map<String, Object>` but is
  catalog-level, not per-mapping.
  `Control.java:59-61`
- `RiskAssessmentResult.inputFactors` / `computedOutputs` exist but live
  on the assessment, not on the mapping.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskAssessmentResult.java:55-57, 75-77`
- The preflight (§Boundary) explicitly forbids treating the assessment
  as the mapping: "do not push those attributes into
  `RiskAssessmentResult` as if the assessment itself were the mapping".

**Gap.** A mapping needs to record (and validate against
`MethodologyProfile.inputSchema` / `outputSchema`) the influence the
control exerts on the scenario *under a specific methodology*. The
preflight (§Incumbents) names the reusable storage shape:
`JacksonTextCollectionConverters` for an arbitrary structured payload,
not a feature-local parser. Today there is no field to put it in.

### C5 - Unmapped scenarios OR register records. **UNSATISFIED.**

The requirement covers both kinds of mapping endpoint on the risk side.
Neither has the query.

**Two filters apply to every unmapped query, plus a structural
prerequisite.**

1. **`link_type` filter.** Both link tables carry semantic `link_type`
   values that distinguish a mitigation/mapping from a generic
   association. `RiskScenarioLink` uses `RiskScenarioLinkType` -
   `MITIGATED_BY` is the mitigation form; `ASSOCIATED` is not.
   `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/RiskScenarioLinkType.java`
   `ControlLink` uses `ControlLinkType` - `MITIGATES` and `MAPS_TO`
   are the mapping forms; `ASSOCIATED` is not.
   `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/state/ControlLinkType.java`
   A scenario "associated" to a control without a mitigation link
   still has no GC-T003 mapping; counting that scenario as mapped
   would mask the gap GC-T003 wants surfaced.

2. **UNION across both projections.** C1 documents that `ControlLink`
   and `RiskScenarioLink` are independent tables that today can
   diverge. A given mitigation may be recorded on either side, both
   sides, or (in the divergent case) only one side. An unmapped query
   that reads only one table will produce false positives for the
   other table's rows. Until the canonical mapping owner from
   recommendation 1/2 exists, every C5/C6 query must UNION both
   projections: count a scenario as mapped if EITHER a
   `RiskScenarioLink(scenario, target_type=CONTROL,
   link_type=MITIGATED_BY)` OR a `ControlLink(target_type=RISK_SCENARIO,
   target_entity_id=scenario, link_type∈{MITIGATES,MAPS_TO})` exists,
   and symmetrically for the control and record cases.

3. **Structural prerequisite.** The cleanest long-term answer is the
   canonical mapping owner / mechanical consistency invariant from
   recommendation 1/2. Until that lands, both filters above are a
   stop-gap that papers over the C1 divergence risk - they reduce
   false positives but cannot fully detect a mitigation that exists
   only on one side when both sides should have agreed. The unmapped
   queries are therefore conditionally correct on the owner /
   invariant existing, and #258 should treat owner/invariant as a
   prerequisite for shipping the C5/C6 read side.

- **Scenarios with no mapped controls.** Today this requires UNION
  across both projections.
  No scenario-side query exists.
  `RiskScenarioLinkRepository` has reverse-lookup methods keyed on a
  target entity id, but no "scenarios where no link of `target_type =
  CONTROL` AND `link_type ∈ {MITIGATED_BY}` exists" query.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/repository/RiskScenarioLinkRepository.java:15-21, 37-51`
  `RiskScenarioLinkService.listByScenario(...)` lists from a scenario
  outward; it has no "no-mitigation" predicate.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskScenarioLinkService.java:120-128`
  And no control-side reverse query exists either.
  `ControlLinkRepository` has no "scenarios in project P that DO appear
  as `target_entity_id` of any `ControlLink(target_type =
  RISK_SCENARIO, link_type ∈ {MITIGATES, MAPS_TO})`" query, so the
  scenario set covered by the control-side projection cannot be
  computed today.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/repository/ControlLinkRepository.java:14-33`

- **Register records with no mapped controls.** Today this is
  one-sided - and the one side that exists is the control side.
  `RiskRegisterRecordRepository` has no control-mapping query at all,
  because records have no link table of their own (see C1). The
  scenario-to-record edge (`RiskScenarioLink(target_type =
  RISK_REGISTER_RECORD, link_type=REGISTERED_IN)` or similar) does
  NOT establish control coverage of the record - that edge connects
  a scenario to a record and has no control endpoint, so it cannot
  prove the record is mitigated by any control. Including it in the
  UNION would let a record appear "mapped" with zero linked controls,
  which is exactly the gap C5b is meant to surface.
  The correct direct query is therefore single-sided: records in
  project P that DO NOT appear as `target_entity_id` of any
  `ControlLink(target_type = RISK_REGISTER_RECORD, link_type ∈
  {MITIGATES, MAPS_TO})`.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/repository/ControlLinkRepository.java:14-33`
  An optional transitive form (parallel to C6's
  transitive-through-record interpretation but in the other direction)
  exists: a record can be considered indirectly mitigated when its
  `RiskRegisterRecord.riskScenarios` set is non-empty AND each of
  those scenarios is itself mitigated (per C5a). The transitive form
  flows through a real control endpoint at each step, so it is sound;
  the scenario-to-record link is NOT a substitute for it.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskRegisterRecord.java:67-72`

- The MCP analyze tools are requirement-traceability-centric only:
  - `gc_analyze_orphans` finds requirements with no relations to other
    requirements. `mcp/ground-control/index.js:1124-1139`
  - `gc_analyze_coverage_gaps` finds requirements missing a specific
    traceability link type. `mcp/ground-control/index.js:1141-1157`
  Neither addresses the domain query GC-T003 needs.

**Gap.** Two new queries (and a thin service/MCP layer), scoped by
project and optionally by asset/boundary, filtered to the mapping
`link_type` set so generic `ASSOCIATED` rows do not mask the gap, and
conditioned on the canonical mapping owner / consistency invariant
from recommendation 1/2 to fully eliminate the C1 divergence risk:
  (a) **C5a - UNION across both control↔scenario projections.**
  Scenarios in P that appear in NEITHER the scenario-side projection
  (`RiskScenarioLink(target_type=CONTROL, link_type=MITIGATED_BY)`)
  NOR the control-side reverse projection
  (`ControlLink(target_type=RISK_SCENARIO, link_type ∈ {MITIGATES,
  MAPS_TO})` with `target_entity_id = scenario_id`).
  (b) **C5b - direct + optional transitive (no scenario-side
  shortcut).** Direct: records in P that do NOT appear as
  `target_entity_id` of any `ControlLink(target_type=RISK_REGISTER_RECORD,
  link_type ∈ {MITIGATES, MAPS_TO})`. Optional transitive (the
  symmetric counterpart to C6's transitive-through-record path):
  also exclude records whose `riskScenarios` set is non-empty and
  whose every scenario is itself mitigated per C5a. The
  `RiskScenarioLink(target_type=RISK_REGISTER_RECORD, …)` edge is
  NOT counted because it has no control endpoint.
The preflight (§Extensibility) requires this read side be reusable by
API, MCP, graph, and verification surfaces - not buried in one-off
report logic.

### C6 - Controls not mapped to any relevant scenario. **UNSATISFIED.**

The requirement statement's C6 wording is "controls not mapped to any
relevant scenario." Two interpretations of "relevant scenario" are
defensible against the existing model; #258 must pick one and
document the choice:

- **Literal-direct interpretation.** A control is "mapped to a
  relevant scenario" only if there is a *direct* mitigation row on
  either side: `ControlLink(target_type=RISK_SCENARIO,
  link_type ∈ {MITIGATES, MAPS_TO})` from the control or
  `RiskScenarioLink(target_type=CONTROL, link_type=MITIGATED_BY,
  target_entity_id=control)` from the scenario.
  Cost: controls mitigating a register record that owns scenarios are
  flagged as unmapped, producing noisier reports for record-driven
  workflows.
- **Transitive-through-record interpretation.** A control is also
  "mapped to a relevant scenario" if it is mapped to a register
  record whose `riskScenarios` set is non-empty - that is, the
  record-side mapping transitively covers the record's scenarios.
  Anchored in `RiskRegisterRecord.riskScenarios`.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskRegisterRecord.java:67-72`
  Cost: a control that mitigates a record with zero linked scenarios
  is still flagged as unmapped; a control covering only forward-
  looking records that have not yet been linked to scenarios surfaces
  the gap correctly but may confuse operators who consider any record
  coverage "enough."

In either interpretation, the same `link_type` and UNION-across-projections
filters from C5 apply, and the canonical mapping owner from
recommendation 1/2 is the structural prerequisite that retires the
UNION stop-gap.

- `ControlLinkRepository` has only forward-lookup methods (controls →
  their links). It has no "controls in this project with zero direct
  or transitive scenario mappings" query under either interpretation.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/repository/ControlLinkRepository.java:14-33`
- `ControlLinkService.listByControl(...)` lists from a control outward.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlLinkService.java:65-72`

**Gap.** A new query is required, scoped by project and optionally by
asset/boundary, returning controls with no mitigation to any relevant
scenario under whichever interpretation #258 chooses. The literal-direct
form is the closer match to the requirement's words; the
transitive-through-record form is the closer match to the requirement's
intent given that GC-T003 itself names records as valid risk-side
endpoints. Either way, the query must UNION the scenario-side
(`RiskScenarioLink(target_type=CONTROL, ..., target_entity_id=control,
link_type=MITIGATED_BY)`) and control-side
(`ControlLink(target_type=RISK_SCENARIO, link_type ∈ {MITIGATES,
MAPS_TO})`) projections to avoid false positives from C1 divergence.

### C7 - GC-I013 evaluation results feed linked risk assessments. **BLOCKED-BY-DEPENDENCY.**

GC-I013 is DRAFT. The preflight (§Boundary) is explicit:
> until a first-class control effectiveness assessment model exists,
> do not pretend `VerificationResult` satisfies GC-I013. It is
> currently a prover/result artifact tied to requirements and
> traceability links, not a control evaluation aggregate.

- `RiskAssessmentResult` has FKs to `RiskScenario`, `MethodologyProfile`,
  and optional `RiskRegisterRecord`, but no FK to a control or to a
  control-evaluation aggregate.
  `RiskAssessmentResult.java:37-47`
- No `ControlEffectivenessAssessment` entity exists in the codebase.
- `RiskAssessmentResult.evidenceRefs` is a `List<String>`, not a
  structured edge to a control evaluation.
  `RiskAssessmentResult.java:83-85`

**Gap.** Unblocking C7 requires GC-I013 to land first (or to be
explicitly scoped against the future model). This PR does not
implement GC-I013.

### C8 - Mapping-anchored observations / evidence feed linked risk assessments. **PARTIAL.**

The reusable infrastructure exists on the assessment side, but the
GC-T003 propagation - *from a mapping into a linked assessment* - does
not. The reviewers caught me crediting generic assessment-side storage
toward the mapping-side feed.

- **Reusable infrastructure (not the GC-T003 contract).**
  `RiskAssessmentResult` has an explicit many-to-many to `Observation`
  via `risk_assessment_result_observation`. This proves *assessments
  can attach observations* - it does not prove *mapped observations
  flow from a mapping into a linked assessment*.
  `RiskAssessmentResult.java:90-95`
  Evidence on an assessment is captured only as
  `List<String> evidenceRefs` - free-form identifiers, not a typed
  link.
  `RiskAssessmentResult.java:83-85`

- **What is actually missing.** Neither `ControlLinkService.create()`
  nor `RiskScenarioLinkService.create()` loads, validates, or attaches
  observation / evidence context anchored on the mapping, and there is
  no service-level mechanism that propagates a mapping's observations
  / evidence into the `RiskAssessmentResult` linked to the same
  scenario or record.
  `ControlLinkService.java:29-63`,
  `RiskScenarioLinkService.java:37-81`

**Why the existing assessment-side join is not enough on its own.**
The `risk_assessment_result_observation` table records "this
assessment includes this observation." It carries no information
about which control-to-risk mapping supplied the observation; it
cannot validate that a contributed observation is in scope for any
specific mapping; and when one mapping changes (added,
re-scoped, deleted) while other mappings still feed the same
assessment, there is no way to retract or re-evaluate only that
mapping's contribution. So treating
`RiskAssessmentResult ↔ Observation` as the entire C8 storage layer
silently drops mapping provenance.

**Gap.** Three distinct decisions for #258:
  (a) **Define the mapping-owned provenance edge.** Either an
  explicit `mapping_observation` (and `mapping_evidence`) relation /
  graph edge that records which mapping anchored the observation /
  evidence, OR an explicit narrowing of C8 so the "anchored on a
  mapping" language drops out and observations/evidence feed
  assessments without mapping provenance. The plain
  `RiskAssessmentResult ↔ Observation` join is insufficient
  on its own - the mapping provenance does not have to live in a
  duplicate `Observation` model, but it does have to exist
  somewhere addressable.
  `RiskAssessmentResult.java:90-95`
  (b) **Define the propagation trigger.** Specify how observations
  and evidence anchored on a mapping flow into the
  `RiskAssessmentResult` linked to the same scenario or register
  record (service-layer push, read-time join over the new
  provenance relation, or shared graph projection). The
  trigger-from-mapping is what the existing service flow
  (`ControlLinkService.create()`, `RiskScenarioLinkService.create()`)
  lacks today.
  `ControlLinkService.java:29-63`,
  `RiskScenarioLinkService.java:37-81`
  (c) **Decide the evidence shape.** Keep `evidenceRefs` as strings
  and explicitly scope GC-T003 around that, or upgrade `evidenceRefs`
  to a typed `evidence_link`. The (c) decision is independent of
  (a) and (b) but is the cleanest place to also carry mapping
  provenance if (a) routes evidence through it.
Until all three decisions land, C8 reads as PARTIAL.

## Asymmetry caveat surfaced during verification

`RiskScenarioLinkService.create()` validates internal targets via
`GraphTargetResolverService.validateRiskScenarioTarget(...)` at
`RiskScenarioLinkService.java:49-50`. `ControlLinkService.create()`
does not call any graph target resolver - it accepts any
`(targetType, targetEntityId, targetIdentifier)` triple without
project-scope validation.
`ControlLinkService.java:29-49`

This is independently called out in the preflight (§Incumbents): the
GC-T003 implementation must not model itself after the looser
`ControlLink` behavior. The fix belongs on issue #258 alongside the
schema work - same-project target validation is a precondition for any
mapping that wants to claim semantic consistency.

## Dependency status

- **GC-I001 Control Catalog** - **ACTIVE** (verified via
  `gc_get_requirement`). Catalog-level control identity, objective,
  function, status, and effectiveness summary exist on `Control`.
  `Control.java:33-65` Sufficient to anchor C3's catalog side. The
  mapping-specific overlay is still needed.
- **GC-I013 Control Effectiveness Assessment** - **DRAFT**. Blocks C7.

## Recommendation to #258

The implementation issue should treat the following as the minimum
set of GC-T003 work, in the order the preflight (§Boundary,
§Incumbents, §Extensibility) requires:

1. **Decide the mapping endpoints.** GC-T003's requirement statement
   is `controls OR scoped control implementations` × `scenarios OR
   register records`. The implementation must explicitly handle all
   four cross-products or document a deliberate narrowing:
   - **Scenario-side bidirection** is two-sided today (RiskScenarioLink
     and ControlLink both carry the mapping).
   - **Record-side bidirection** is one-sided today (only ControlLink
     points at records; there is no `RiskRegisterRecordLink`). Add the
     missing side - either a `RiskRegisterRecordLink` entity or a
     record-side service that reads through `ControlLinkRepository` via
     a new reverse query.
   - **Scoped control implementations** have no first-class entity.
     Choose: (a) introduce a `ScopedControlImplementation` entity that
     becomes a `target_type` on the risk-side links; (b) treat
     `ControlLink` artifact-target rows (`ASSET`, `CODE`,
     `CONFIGURATION`, `OPERATIONAL_ARTIFACT`) as the scoped projection
     and document the choice; or (c) scope GC-T003 to catalog controls
     only and note the deviation in the requirement statement.

2. **Decide the mapping owner.** Extend `ControlLink` /
   `RiskScenarioLink` (and the record-side equivalent from item 1)
   with mapping-context columns and a mechanical consistency
   invariant, OR introduce a dedicated mapping entity
   (`RiskControlMapping`) that the link rows project from.

3. **Add mapping-row columns** for control objective
   (mapping-specific), control role (mapping-specific), scope
   (mapping-specific), and an asset/boundary anchor.

4. **Add a structured methodology-influence payload** validated
   against the relevant `MethodologyProfile.inputSchema`. Reuse
   `JacksonTextCollectionConverters`.

5. **Add reverse-lookup queries and a thin service/MCP layer.**
   Structural prerequisite: ship the canonical mapping
   owner/consistency invariant from items 1–2 first, then point the
   queries at that owner directly. Until that exists, every query
   must (a) filter to the mitigation/mapping `link_type` set so
   generic `ASSOCIATED` rows do not mask gaps, AND (b) UNION across
   both `ControlLink` and `RiskScenarioLink` projections so a
   one-sided mitigation row does not produce a false positive.
   - **C5a (scenarios with no mapped controls).** Scenarios in
     project P that appear in NEITHER the scenario-side projection
     (`RiskScenarioLink(target_type=CONTROL, link_type=MITIGATED_BY)`)
     NOR the control-side reverse projection
     (`ControlLink(target_type=RISK_SCENARIO, link_type ∈ {MITIGATES,
     MAPS_TO})` with `target_entity_id = scenario_id`).
   - **C5b (records with no mapped controls).** Direct form: records
     in P that do NOT appear as `target_entity_id` of any
     `ControlLink(target_type=RISK_REGISTER_RECORD, link_type ∈
     {MITIGATES, MAPS_TO})`. The scenario-to-record edge
     (`RiskScenarioLink(target_type=RISK_REGISTER_RECORD, …)`) is
     NOT counted - it has no control endpoint and cannot prove the
     record is mitigated. Optional transitive form (parallel to C6's
     transitive-through-record path, flowing through a real control
     at each step): also exclude records whose
     `RiskRegisterRecord.riskScenarios` set is non-empty and whose
     every scenario is itself mitigated per C5a.
   - **C6 (controls not mapped to any relevant scenario).** Pick a
     coverage interpretation (literal-direct OR transitive-through-record,
     per the C6 section) and document the choice in #258. In either
     interpretation, UNION the scenario-side
     (`RiskScenarioLink(target_type=CONTROL, ...,
     target_entity_id=control, link_type=MITIGATED_BY)`) and
     control-side (`ControlLink(target_type=RISK_SCENARIO, link_type ∈
     {MITIGATES, MAPS_TO})`) projections, then exclude controls with a
     match. The transitive-through-record form additionally honors
     `RiskRegisterRecord.riskScenarios` as a coverage path for
     controls mapped only to records that own scenarios.
   Scope all queries by project and optionally by asset/boundary, and
   expose them via API and MCP per the preflight's read-side reuse
   rule (§Extensibility).

6. **Close the target-validation asymmetry** between
   `ControlLinkService.create()` and
   `RiskScenarioLinkService.create()` by routing both - and any new
   record-side service - through `GraphTargetResolverService`.

7. **Define the mapping-to-assessment observation / evidence feed
   (C8).** Three sub-decisions, all required:
   - **Mapping-owned provenance edge** (or explicit C8 narrowing) -
     because the existing `RiskAssessmentResult ↔ Observation` join
     records assessment↔observation but not mapping↔observation, it
     cannot carry which mapping supplied a given observation. Add a
     `mapping_observation` / `mapping_evidence` relation (or graph
     edge) so retraction, re-scoping, and per-mapping validation are
     possible. Alternatively, narrow C8 in the requirement statement
     so the "anchored on a mapping" phrasing is dropped.
   - **Propagation trigger** - specify how mapping-anchored
     observations / evidence reach the `RiskAssessmentResult` linked
     to the same scenario or record (service-layer push, read-time
     join over the new provenance relation, or shared graph
     projection). This is what `ControlLinkService.create()` and
     `RiskScenarioLinkService.create()` lack today.
   - **Evidence shape** - keep `evidenceRefs` as strings (and scope
     GC-T003 around that) or upgrade to a typed `evidence_link` that
     can also carry mapping provenance.

8. **Defer C7** wire to assessments until GC-I013 lands.

This list is consistent with the preflight guardrails; it is not a
design - design lives on #258 once the work is taken on.
