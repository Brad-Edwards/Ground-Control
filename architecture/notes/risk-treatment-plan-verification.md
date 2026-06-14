# GC-T004 Risk Treatment Plans - Verification Record

Issue: #825
Implementation issue: #259
Requirement: GC-T004 (DRAFT, wave 4)
Branch verified: `825-verify-gc-t004-risk-treatment-plans-clause-by-clause-audit-transition-draft→active-reconcile-traceability`
Base: `dev` at `0522de2` (pre-verification HEAD; verification evidence captured against this branch, which carries three documentation files as the full diff from base - this verification note, its companion preflight note `architecture/notes/risk-treatment-plan-preflight.md`, and the towncrier changelog fragment `changelog.d/825.changed.md`. No production-code changes.)
Companion: [`risk-treatment-plan-preflight.md`](risk-treatment-plan-preflight.md)

This note records the clause-by-clause verification of GC-T004 against
the current codebase and the gaps that drive implementation issue #259.
The preflight note records architecture guardrails for the future
implementation; this note records where the requirement actually stands
today.

## Verdict

**GC-T004 stays DRAFT.** Four clauses SATISFIED (C1, C2, C3, C7),
three PARTIAL (C4, C5, C6), one UNSATISFIED (C8). The dispositive
failure is C8 - reassessment-trigger behavior - which has neither
categorised trigger shape nor any event/listener wiring on the three
change sources the requirement names (treatment progress, asset state,
control state). C4 is reclassified PARTIAL because the
`ControlLinkTargetType.TREATMENT_PLAN` enum value is necessary but
not sufficient: `ControlLinkService.create` writes the supplied
`targetEntityId` straight into the link without resolving it through
`GraphTargetResolverService`, so a control can be "linked" to a
non-existent or cross-project treatment-plan UUID without any
validation. The clause-by-clause table below is the authoritative
count; this summary line and the changelog fragment must match it.

| Clause | Verdict |
| ------ | ------- |
| C1 Treatment plan linked to a risk register record | SATISFIED |
| C2 Optional link to underlying risk scenarios | SATISFIED |
| C3 Optional link to operational asset scope | SATISFIED |
| C4 Optional link to controls implementing mitigation | PARTIAL |
| C5 Strategies: mitigate, accept, transfer, share, avoid plus methodology-specific equivalents | PARTIAL |
| C6 Action items with owner and due date | PARTIAL |
| C7 Status tracking | SATISFIED |
| C8 Triggers for reassessment when treatment progress / asset state / control state changes | UNSATISFIED |

## Requirement statement (for reference)

> The system shall support risk treatment plans linked to risk register
> records and, when applicable, the underlying risk scenarios,
> operational asset scope, and controls implementing mitigation.
> Treatment plans shall support strategies such as mitigate, accept,
> transfer, share, avoid, or methodology-specific equivalents, action
> items with owner and due date, status tracking, and triggers for
> reassessment when treatment progress, asset state, or control state
> changes.

The issue body decomposes the statement into 8 clauses; this audit
follows that decomposition.

## Clause-by-clause evidence

### C1 - Treatment plan linked to a risk register record. **SATISFIED.**

`TreatmentPlan.riskRegisterRecord` is a mandatory `@ManyToOne` with
`optional = false` and a `nullable = false` join column.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/TreatmentPlan.java:41-43`

The constructor requires the record at construction time so a row can
never exist without one.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/TreatmentPlan.java:78-89`

`TreatmentPlanService.create` resolves the record by id+project via
`findByIdAndProjectIdWithScenarios` and throws `NotFoundException` when
absent, so cross-project record references are rejected at the service
boundary.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/TreatmentPlanService.java:42-46`

The schema enforces NOT NULL with `ON DELETE CASCADE` and a project
scope.
`backend/src/main/resources/db/migration/V043__create_risk_assessment.sql:96-116`

The graph projection emits a `TREATS` edge from `TREATMENT_PLAN` to
`RISK_REGISTER_RECORD` for every plan, so the linkage is visible in
graph traversal.
`backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/RiskGraphProjectionContributor.java:225-237`

### C2 - Optional link to underlying risk scenarios. **SATISFIED.**

`TreatmentPlan.riskScenario` is an optional `@ManyToOne` with
`ON DELETE SET NULL` semantics in the schema.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/TreatmentPlan.java:45-47`,
`backend/src/main/resources/db/migration/V043__create_risk_assessment.sql:96-116`

Service-layer validation enforces the preflight contract that the
scenario must be in the same project AND, when the linked register
record carries scenario links, the scenario must be one of those:

```
if (riskScenarioId != null) {
    var scenario = riskScenarioRepository
            .findByIdAndProjectId(riskScenarioId, projectId)
            .orElseThrow(() -> new NotFoundException(...));
    if (!plan.getRiskRegisterRecord().getRiskScenarios().isEmpty()
            && plan.getRiskRegisterRecord().getRiskScenarios().stream()
                    .noneMatch(candidate -> candidate.getId().equals(scenario.getId()))) {
        throw new DomainValidationException(
                "Treatment plan scenario must belong to the linked risk register record");
    }
    plan.setRiskScenario(scenario);
}
```

`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/TreatmentPlanService.java:131-142`

`RiskScenarioLinkTargetType.TREATMENT_PLAN` also exists for the
scenario-side reverse edge, and the graph projection maps that target
type into a `TREATMENT_PLAN` edge.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/RiskScenarioLinkTargetType.java:12`,
`backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/RiskGraphProjectionContributor.java:256`

### C3 - Optional link to operational asset scope. **SATISFIED.**

`AssetLinkTargetType.TREATMENT_PLAN` is a first-class target type, so
an `AssetLink` row can name a treatment plan as the target of an asset
linkage.
`backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/AssetLinkTargetType.java:9`

`GraphTargetResolverService.validateAssetTarget` accepts
`TREATMENT_PLAN` as a valid asset-link target.
`backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/GraphTargetResolverService.java:86-91`

The preflight specifically scopes asset-scope linkage to the existing
`AssetLink` surface and the `RiskRegisterRecord` / `RiskScenario`
hand-off, not to a treatment-plan-specific asset table - that contract
is honored.
`architecture/notes/risk-treatment-plan-preflight.md:46-48`

### C4 - Optional link to controls implementing mitigation. **PARTIAL.**

`ControlLinkTargetType.TREATMENT_PLAN` is a first-class target type, so
a `ControlLink` row can name a treatment plan as the target of a
control implementation linkage.
`backend/src/main/java/com/keplerops/groundcontrol/domain/controls/state/ControlLinkTargetType.java:8`

The preflight scopes control-mitigation linkage to the existing
`ControlLink` surface plus existing control status/effectiveness fields,
not to a treatment-specific control join - that contract is honored.
`architecture/notes/risk-treatment-plan-preflight.md:49-53`

The requirement only requires that linkage be possible "when
applicable"; the audit does not require an enforcement that *every*
treatment plan declare a control. None is required.

**Gap.** The enum value is necessary but not sufficient.
`ControlLinkService.create` writes `command.targetEntityId()` straight
into the saved `ControlLink` without going through
`GraphTargetResolverService`:

```
public ControlLink create(UUID projectId, UUID controlId, CreateControlLinkCommand command) {
    var control = controlService.getById(projectId, controlId);

    if (command.targetEntityId() != null
            && controlLinkRepository.existsByControlIdAndTargetTypeAndTargetEntityIdAndLinkType(
                    controlId, command.targetType(), command.targetEntityId(), command.linkType())) {
        throw new ConflictException("Duplicate control link");
    }
    ...
    var link = new ControlLink(
            control,
            command.targetType(),
            command.targetEntityId(),
            command.targetIdentifier(),
            command.linkType());
    ...
}
```

`backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlLinkService.java:29-49`

The service neither injects `GraphTargetResolverService` nor calls any
target-validation method on it. Consequently a caller can submit
`targetType = TREATMENT_PLAN` with a UUID that names no existing
treatment plan, names a treatment plan in a different project, or
names a row of an entirely different type - all are accepted and
persisted. `ControlGraphProjectionContributor` then projects an edge
keyed on the raw UUID, so the bad reference also leaks into graph
traversal.

This is the only `*LinkService` in the codebase that bypasses the
resolver. The asset, risk-scenario, and threat-model link services all
inject `GraphTargetResolverService` and validate internal targets
through it:
`backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetService.java:17,35,43,255-269`,
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskScenarioLinkService.java:5,26,31`,
`backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/service/ThreatModelLinkService.java:5,24,29`

The preflight names project-scoped target validation as a binding
contract for treatment linkage: "Internal target validation: use or
extend `GraphTargetResolverService` when a link surface accepts
first-class internal targets."
`architecture/notes/risk-treatment-plan-preflight.md:54-57`

This is PARTIAL because the linkage is *expressible* via the enum but
not *validated*; the requirement says "links to controls implementing
the mitigation" and an unvalidated reference is not a real link.

### C5 - Strategies: mitigate, accept, transfer, share, avoid plus methodology-specific equivalents. **PARTIAL.**

The five canonical strategies are present as enum values:

```
public enum TreatmentStrategy {
    MITIGATE,
    ACCEPT,
    TRANSFER,
    SHARE,
    AVOID,
    OTHER
}
```

`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/TreatmentStrategy.java:3-9`

The strategy is enforced as `NOT NULL` on the entity (`@Enumerated`,
`nullable = false`), the API DTO (`@NotNull TreatmentStrategy strategy`),
and the database column (`strategy VARCHAR(20) NOT NULL`).
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/TreatmentPlan.java:49-51`,
`backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/TreatmentPlanRequest.java:18`,
`backend/src/main/resources/db/migration/V043__create_risk_assessment.sql:96-116`

**Gap.** "Methodology-specific equivalents" reduces to a single `OTHER`
enum value with no methodology binding and no equivalent name. A
NIST-specific strategy and a FAIR-specific strategy land in the same
`OTHER` cell with no semantic distinction; downstream consumers cannot
tell them apart, cannot validate them against the project's
`MethodologyProfile`, and cannot route on them.

`MethodologyProfile` carries no treatment-strategy vocabulary today -
the entity has `inputSchema` and `outputSchema` JSON fields but no
strategy-name list, and `TreatmentPlan` has no
`MethodologyProfile` join.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/MethodologyProfile.java:50-58`

The preflight names the canonical fix and explicitly forbids the
shortcut: "Do not hide methodology-specific strategy values in
arbitrary action-item keys when the API enum or methodology profile
should carry the contract."
`architecture/notes/risk-treatment-plan-preflight.md:38-41,124-126`

This is PARTIAL because the canonical five strategies are correctly
modeled and the SHOULD-priority requirement is partially honored via
`OTHER` as an escape hatch; the methodology binding remains absent.

### C6 - Action items with owner and due date. **PARTIAL.**

`actionItems` is a JSON-text column converted to and from
`List<Map<String, Object>>`.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/TreatmentPlan.java:66-68`

The DTO request and response both carry `List<Map<String, Object>>`
unchanged.
`backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/TreatmentPlanRequest.java:23`,
`backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/TreatmentPlanResponse.java:28`

**Gap.** There is no per-action-item typed contract for `owner` and
`dueDate`. A caller can submit `[{"owner": "x", "dueDate": "..."}]` or
`[{"foo": 1}]` or `[]`; both pass JSON deserialization. There is no
Bean Validation gate, no field-level required key, no per-item
validator, and no service-layer check that any action item has an
owner or a due date. The plan-level `owner` and `dueDate`
(`TreatmentPlan.owner`, `TreatmentPlan.dueDate`,
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/TreatmentPlan.java:53-60`)
are scoped to the plan as a whole, not to each action item, so they
do not satisfy "action items with owner and due date" - a plan with
ten action items has one shared owner and one shared due date.

The preflight names the canonical fix: "introduce one canonical
action-item value shape or child entity with explicit `owner`,
`dueDate`, `status`, and optional assignee identity, then map current
JSON payloads into that shape."
`architecture/notes/risk-treatment-plan-preflight.md:104-109`

This is PARTIAL because the data is reachable and round-trips through
the API but no contract enforces the per-item shape the requirement
specifies.

### C7 - Status tracking. **SATISFIED.**

`TreatmentPlanStatus` is a five-state enum with an explicit
`validTargets` map and `canTransitionTo` predicate:

```
public enum TreatmentPlanStatus {
    PLANNED,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    CANCELED;

    public Set<TreatmentPlanStatus> validTargets() {
        return switch (this) {
            case PLANNED -> Set.of(IN_PROGRESS, CANCELED);
            case IN_PROGRESS -> Set.of(BLOCKED, COMPLETED, CANCELED);
            case BLOCKED -> Set.of(IN_PROGRESS, CANCELED);
            case COMPLETED, CANCELED -> Set.of();
        };
    }
    ...
}
```

`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/TreatmentPlanStatus.java:5-23`

`TreatmentPlan.transitionStatus` enforces the transition map and throws
`DomainValidationException` on invalid moves - terminal states
(`COMPLETED`, `CANCELED`) cannot transition out.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/TreatmentPlan.java:91-96`

`TreatmentPlanService.transitionStatus` exposes the operation, and the
controller has a dedicated `PUT /api/v1/treatment-plans/{id}/status`
endpoint.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/TreatmentPlanService.java:85-89`,
`backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/TreatmentPlanController.java:94-101`

Status is also honored at creation: the service accepts an initial
status in `CreateTreatmentPlanCommand` and applies it through the same
state machine, so the system cannot be tricked into bootstrapping a
plan into a state unreachable from `PLANNED`.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/TreatmentPlanService.java:47-49`

Tests cover transitions and reject invalid moves at both controller and
service layers.
`backend/src/test/java/com/keplerops/groundcontrol/unit/api/TreatmentPlanControllerTest.java`,
`backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TreatmentPlanServiceTest.java`

### C8 - Triggers for reassessment when treatment progress, asset state, or control state changes. **UNSATISFIED.**

This clause has both a *shape* aspect ("triggers for reassessment...")
and a *behavior* aspect ("...when treatment progress, asset state, or
control state changes"). Neither is implemented today.

**Shape gap.** `reassessmentTriggers` is a `List<String>` of free-text
labels stored as JSON.
`backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/TreatmentPlan.java:70-72`

There is no trigger-category enum (for example,
`TREATMENT_PROGRESS_CHANGED`, `ASSET_STATE_CHANGED`,
`CONTROL_STATE_CHANGED`, `ASSESSMENT_REFRESH`,
`METHODOLOGY_SPECIFIC`) and no target reference resolved through
`GraphTargetResolverService`. The preflight is explicit about what the
shape needs to be when the triggers become machine-actionable:
"Reassessment triggers should remain expressed as trigger categories
plus target references when they become machine-actionable: treatment
progress, asset-state change, control-state change, assessment refresh,
or methodology-specific trigger. The target-resolution seam belongs in
`GraphTargetResolverService` and graph projections so API, MCP, and
future analysis/sweep surfaces can reuse the same project-scoped
validation."
`architecture/notes/risk-treatment-plan-preflight.md:111-116`

**Behavior gap (the dispositive one).** Three change sources are named
by the requirement; none of them fire any reassessment behavior today.

- **Treatment progress changes.** `TreatmentPlanService.transitionStatus`
  saves the entity and returns; no `ApplicationEventPublisher` is
  injected, no event is published, no listener is wired.
  `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/TreatmentPlanService.java:85-89`
- **Asset state changes.** A grep for `ApplicationEventPublisher` and
  `publishEvent` across the entire `domain/assets/` tree returns zero
  hits.
  `grep -rn "ApplicationEventPublisher\|publishEvent" backend/src/main/java/com/keplerops/groundcontrol/domain/assets/`
  → empty.
- **Control state changes.** Same grep against `domain/controls/` -
  empty.
  `grep -rn "ApplicationEventPublisher\|publishEvent" backend/src/main/java/com/keplerops/groundcontrol/domain/controls/`
  → empty.

The only event-driven plumbing in the entire main tree is for
requirement embeddings - `RequirementService` publishes,
`EmbeddingService` listens via `@TransactionalEventListener`. Neither
listens for treatment, asset, or control change events.
`backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java:18,32,38`,
`backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/EmbeddingService.java:16,206`

A grep for the `reassess` token across the entire main tree returns
only the field name itself and its setters/getters - there is no
`reassess`-handling code path anywhere.
`grep -rn "reassess\|Reassess\|REASSESS" backend/src/main/java/`

This is UNSATISFIED because the requirement says the system "shall
support... triggers for reassessment when [...] changes" - the system
does not. Storing a list of trigger labels in a JSON column is
sufficient as a documentation aid, not as a satisfied behavior.

The preflight explicitly forbids declaring this clause satisfied
without trigger-behavior evidence: "Do not transition GC-T004 to ACTIVE
unless each clause is evidenced against the canonical artifacts,
including asset/control linkage and reassessment trigger behavior."
`architecture/notes/risk-treatment-plan-preflight.md:132-134`

## Gap consolidation for #259

The implementation issue receives the same evidence above, restated as
four concrete work items:

1. **C4 - project-scoped `ControlLink` target validation.** Inject
   `GraphTargetResolverService` into `ControlLinkService` (mirroring
   `AssetService`, `RiskScenarioLinkService`, and `ThreatModelLinkService`)
   and resolve `command.targetEntityId()` through it before saving a
   `ControlLink`. The resolver must reject non-existent UUIDs and
   cross-project references for every internal modeled target type the
   `ControlLinkTargetType` enum carries - `ASSET`, `RISK_SCENARIO`,
   `RISK_REGISTER_RECORD`, `RISK_ASSESSMENT_RESULT`, `TREATMENT_PLAN`,
   `METHODOLOGY_PROFILE`, `OBSERVATION`, and `REQUIREMENT`. The
   remaining enum values (`EVIDENCE`, `FINDING`, `CODE`,
   `CONFIGURATION`, `OPERATIONAL_ARTIFACT`, `EXTERNAL`) are
   external/artifact pointers that fall to the `targetIdentifier`
   path, not `targetEntityId`. Service tests need to cover the
   rejection path per internal target type. (Preflight `:54-57`.)
2. **C5 - methodology-strategy binding.** Add a typed
   `methodologyStrategyKey` field on `TreatmentPlan` that joins to a
   strategy vocabulary owned by `MethodologyProfile` (or its profile
   key), with service-layer validation that the key resolves under the
   same project's profile. Required only when `strategy = OTHER`.
   The preflight currently treats `OTHER` as "the current
   methodology-specific escape hatch," so the data model is already
   pointing at this binding as the canonical fix.
   (Preflight `:38-41,124-126`.)
   *Not an implementation path:* narrowing GC-T004's scope to the
   canonical five strategies and documenting the deviation would be a
   requirement amendment, not an implementation. GC-T004 explicitly
   says "or methodology-specific equivalents," so that path is a
   change to the requirement statement and must go through a separate
   requirement-amendment workflow before #259 can rely on it. Until
   such an amendment is accepted, GC-T004 remains DRAFT and #259
   cannot close on the canonical-five-only interpretation.
3. **C6 - typed action items.** Replace
   `List<Map<String, Object>> actionItems` with a typed action-item
   value shape or child entity carrying explicit `owner`, `dueDate`,
   `status`, and optional `assignee` identity. Map existing JSON
   payloads on read; reject untyped writes; gate per-item required
   fields with Bean Validation. (Preflight `:104-109`.)
4. **C8 - categorised triggers + event/listener wiring.**
   - Replace `List<String> reassessmentTriggers` with a typed list of
     `(category, optionalTargetRef)` pairs where category is an enum
     of `TREATMENT_PROGRESS_CHANGED`, `ASSET_STATE_CHANGED`,
     `CONTROL_STATE_CHANGED`, `ASSESSMENT_REFRESH`,
     `METHODOLOGY_SPECIFIC`, and target refs resolve through
     `GraphTargetResolverService`. (Preflight `:111-116`.)
   - Inject `ApplicationEventPublisher` into every mutation path
     that constitutes a "state change" relevant to GC-T004 - not
     only the named status-transition methods. Coverage matrix:
     - **Treatment progress.** Plan-level transitions
       (`TreatmentPlanService.transitionStatus`) AND any mutation
       that flips a typed action-item's `status` once C6 lands the
       typed shape - today action-item mutations flow through
       `TreatmentPlanService.applySharedUpdates` (line 155 sets
       `actionItems` wholesale), so the C6 work changes that path's
       shape and the publisher must hook every action-item status
       transition the new shape exposes.
     - **Asset state.** `AssetService.archive` (lifecycle terminal),
       plus property-level updates on risk-bearing fields via
       `AssetService.update`. `AssetService` has no `transitionStatus`
       and no `AssetStatus` today, so the implementation must either
       (a) emit from `update`/`archive` directly with a payload that
       names which risk-bearing fields changed, or (b) add a
       first-class `AssetStatus` enum with a `transitionStatus`
       operation AND retain the property-level publisher for
       non-status risk-bearing field changes - option (b) aligns
       with the rest of the codebase's status-tracking pattern but
       does not eliminate the property-level publisher.
     - **Control state.** `ControlService.transitionStatus`
       (`backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlService.java:109`)
       AND `ControlService.update` for `effectiveness` (and other
       mitigation-context fields the preflight names) - line 72 is
       the existing `update` path. The preflight scopes
       control-mitigation context to control status AND
       effectiveness, so an `effectiveness` mutation is a
       control-state change for GC-T004 even when status is
       unchanged.
     The event payload carries the entity id, the old value of every
     mutated tracked field, the new value of every mutated tracked
     field, and the project id, so the listener can route on the
     specific change.
   - Add a `@TransactionalEventListener` that, given a triggered
     category and a project, walks the link surfaces (`AssetLink`,
     `ControlLink`, `RiskScenarioLink`,
     `TreatmentPlan.riskRegisterRecord`, `TreatmentPlan.riskScenario`)
     to find linked `RiskRegisterRecord` / `RiskAssessmentResult`
     rows and persist a concrete reassessment-needed signal on each
     one. The current codebase exposes no assessment-refresh API
     (this note's C8 evidence section confirms: zero `reassess`
     identifiers in main, no automatic recomputation path on
     `RiskAssessmentResult`), so #259 must define that side effect
     as part of this work. The minimum shape is a
     `reassessmentRequiredAt: Instant` column on
     `RiskAssessmentResult` (and matching field on
     `RiskRegisterRecord` if the requirement target is the
     register-record row), set by the listener and surfaced via the
     existing read API and graph projection so consumers can route
     on it. A future GC-I-series requirement may add an explicit
     re-evaluate operation that consumes this flag; until then, the
     flag itself is the side effect. Project scoping and
     `ActorHolder` propagation follow the existing pattern at
     `RequirementService` (publisher) →
     `EmbeddingService.@TransactionalEventListener` (listener)
     (`backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java:18,32,38`,
     `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/EmbeddingService.java:16,206`).
   - Tests at three layers, mirroring the requirement / embedding
     pattern, with one publisher test per mutation path in the
     coverage matrix above (not only the status-transition paths):
     - **Service layer** - inject a mock
       `ApplicationEventPublisher` into each publishing service and
       assert the right event fires on every covered mutation -
       plan status transition, action-item status change (after C6),
       asset archive, asset update of each risk-bearing field,
       control status transition, control effectiveness update,
       and any other mutation the coverage matrix names. This is
       the *publisher* test.
     - **Listener layer** - invoke the listener method directly with
       a hand-built event payload, OR publish through the real Spring
       `ApplicationContext` in a slice test, and assert the
       reassessment side effect on the affected rows. Mocking the
       publisher in this layer would skip the
       `@TransactionalEventListener` contract - which is exactly what
       this layer is supposed to verify.
     - **Integration** - `@SpringBootTest` `@Tag("integration")` end
       to end across the transactional event boundary, so a real
       mutation in each covered path triggers a real listener
       invocation through Spring and the database.

All four work items belong to #259's scope. The repo workflow models
one issue → one `/implement` run → one PR (review-cycle counter,
traceability reconciliation, and completion semantics are anchored to
that boundary), so the default path is to land all four work items in
a single PR against #259. If the maintainer chooses to land them
independently for sizing reasons, the items must first be split out
into separate child issues so each child anchors its own `/implement`
run and PR - they cannot ride into separate PRs under #259's number.

## What this PR ships

This PR ships only the audit record and a changelog fragment:

- `architecture/notes/risk-treatment-plan-verification.md` (this file)
- `architecture/notes/risk-treatment-plan-preflight.md` (written by
  Step 2.5 codex preflight against issue #825)
- `changelog.d/825.changed.md`

No production code changes. GC-T004 stays DRAFT. The existing
`DOCUMENTS` traceability link from GC-T004 to GitHub issue 825 (created
when #825 was filed) remains; reconciliation creates no new IMPLEMENTS
link from this diff to GC-T004 because the diff does not implement
GC-T004 - it documents the verification.
