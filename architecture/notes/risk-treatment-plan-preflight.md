# Risk Treatment Plan Preflight

Issue: #825
Implementation issue: #259
Requirement: GC-T004

This is architecture guardrail guidance for verification or follow-up
implementation of GC-T004. It is not an implementation plan.

## Boundary

`TreatmentPlan` is the risk-governance action record: the selected treatment
strategy, owner-facing actions, due dates, status, rationale, and reassessment
signals for a risk register record. It must stay separate from:

- `RiskRegisterRecord`, which owns the governance/register entry and its linked
  scenarios, category, review cadence, and decision metadata.
- `RiskScenario`, which owns the scoped loss scenario statement.
- `RiskAssessmentResult`, which owns methodology input factors, computed
  outputs, confidence, uncertainty, evidence references, observations, and
  approval state.
- `OperationalAsset`, `AssetLink`, `ControlLink`, and graph projections, which
  already provide project-scoped operational scope, mitigation-control context,
  and traversal.

Do not turn treatment plans into assessments, threat-model entries, control
implementations, or asset-scope containers. A treatment plan may reference those
concepts, but their authoritative state remains in their existing aggregates and
link surfaces.

## Incumbents To Reuse

- REST shape: `TreatmentPlanController`, request/response records, `@Valid`, and
  `ProjectService.resolveProjectId/requireProjectId`.
- Domain shape: `TreatmentPlan`, `TreatmentPlanService`, command records,
  `TreatmentPlanRepository`, and `TreatmentPlanStatus.transitionStatus` via the
  enum transition pattern.
- Strategy contract: `TreatmentStrategy` is the API/persistence enum for
  mitigate, accept, transfer, share, avoid, and the current methodology-specific
  escape hatch `OTHER`. Do not add parallel strategy strings inside action
  items or metadata.
- Scenario linkage: use `TreatmentPlan.riskRegisterRecord` as mandatory and
  `TreatmentPlan.riskScenario` as optional. When a scenario is supplied, service
  validation must keep it in the same project and, when the register record has
  scenario links, within that record's scenario set.
- Operational asset scope: use `AssetLink` targeting `TREATMENT_PLAN`,
  `RISK_REGISTER_RECORD`, or `RISK_SCENARIO` for graph-native asset/boundary
  context. Narrative summaries remain secondary context only.
- Controls implementing mitigation: use `ControlLink` targeting
  `TREATMENT_PLAN`, `RISK_REGISTER_RECORD`, or `RISK_SCENARIO`, plus existing
  control status/effectiveness fields. Do not create a treatment-specific
  control join table unless the generic link contract is proven insufficient and
  captured in an ADR.
- Internal target validation: use or extend `GraphTargetResolverService` when a
  link surface accepts first-class internal targets. `targetEntityId` is for
  modeled entities; `targetIdentifier` is only for external or not-yet-modeled
  artifacts.
- JSON text columns: structured treatment-plan collections use
  `JacksonTextCollectionConverters`. Do not add feature-local `ObjectMapper`
  parsing or a second JSON schema system.
- MCP adapter shape: `gc_risk_governance` in
  `mcp/ground-control/index.js`, the per-entity `GOVERNANCE_FIELDS`
  allowlist, the flat Zod shape, `pick`, `reqArg`, `toCamelCase`,
  `addAuthorizationHeader`, `RequestError`, and `validateGovernanceStatus` in
  `mcp/ground-control/lib.js`. The MCP contract for
  `entity=treatment_plan` must mirror the backend treatment-plan create/update
  DTO fields for this aggregate, not generic CRUD fields from adjacent
  governance entities.
- Graph: treatment-plan nodes and `TREATS` edges belong in
  `RiskGraphProjectionContributor`, using `GraphEntityType` and `GraphIds`.
  Asset/control/scope edges should flow through the existing link projection
  paths, not a treatment-only graph materializer.
- Audit and persistence: audited entities extend `BaseEntity`, use Envers,
  Flyway migrations, audit-table parity, project scope, and unique constraints
  consistent with the existing risk, asset, and control models.

## Methodology Strategy Binding (#861)

`TreatmentStrategy` remains the canonical API/persistence enum. The five
canonical strategies (`MITIGATE`, `ACCEPT`, `TRANSFER`, `SHARE`, `AVOID`) stay
direct enum values; methodology-specific equivalents must flow through
`strategy = OTHER` plus a typed profile-scoped binding.

The binding should be explicit on `TreatmentPlan`: a nullable
`methodologyProfile` reference plus a bounded `methodologyStrategyKey` scalar.
Do not infer the profile from `RiskAssessmentResult`, `RiskScenario`, or
`RiskRegisterRecord`; those surfaces can have zero, one, or many methodology
contexts over time, and inference would make treatment semantics depend on a
mutable assessment history instead of the treatment decision itself.

`MethodologyProfile` should expose treatment strategy vocabulary as its own
field, not as `inputSchema`, `outputSchema`, `methodologyInfluence`, action-item
metadata, or a new treatment-plan-local schema. A JSON object keyed by stable
strategy key is the smallest extension that matches the existing profile JSON
column pattern (`JacksonTextCollectionConverters.StringObjectMapConverter`) and
keeps profile/pack authors responsible for vocabulary content. If the
vocabulary later needs lifecycle, translations, or cross-profile queries, the
extension seam is a first-class profile vocabulary table behind the same
profile-scoped key contract, not another treatment-plan field.

`TreatmentPlanService` owns the cross-field invariant after create/update
commands are merged into the plan's resulting state:

- When the resulting strategy is `OTHER`, both `methodologyProfileId` and a
  non-blank `methodologyStrategyKey` are required.
- The profile must resolve through `MethodologyProfileRepository` under the
  same project; cross-project and non-existent profile IDs must not fall back to
  family, profile key, or global lookup.
- The strategy key must exist in the resolved profile's strategy vocabulary.
  Missing vocabulary, a non-strategy key, or a blank key is a
  `DomainValidationException` routed through `GlobalExceptionHandler`.
- When the resulting strategy is one of the canonical five, the service clears
  any stored profile/key pair. Do not persist stale methodology bindings beside
  canonical strategy values, and do not reject canonical requests merely because
  a caller supplied ignored methodology fields.

Do not reuse `MethodologyInfluenceValidator` for this check. It validates
risk-control mapping influence payloads against `MethodologyProfile.inputSchema`
(GC-T003 C4), which is assessment/input-factor vocabulary, not treatment
strategy vocabulary. Keep #861 validation local to `TreatmentPlanService` unless
another real consumer appears; only then extract a small profile-vocabulary
lookup helper.

The REST and MCP contracts must move together. If the backend treatment-plan
request/response records gain `methodologyProfileId` and
`methodologyStrategyKey`, `gc_risk_governance` must add the corresponding
snake_case fields to the Zod shape, `TO_CAMEL`, `GOVERNANCE_FIELDS`, and
adapter tests. `MethodologyProfileRequest` / response must expose the profile
strategy vocabulary. Do not tunnel these values through `metadata`, action
items, rationale, or generic description fields.

Persistence must add parent and audit columns in lockstep:
`methodology_profile_id` and `methodology_strategy_key` on `treatment_plan`,
matching audit-table columns on `treatment_plan_audit`, and the profile
vocabulary column on `methodology_profile` plus `methodology_profile_audit`.
Prefer a normal FK from `treatment_plan.methodology_profile_id` to
`methodology_profile(id)` rather than `ON DELETE SET NULL`; silently nulling the
profile would leave `strategy = OTHER` without the vocabulary that gives it
meaning. Any migration versions added here must be listed in
`MigrationSmokeTest` and `RequirementsE2EIntegrationTest` per `.gc/plan-rules.md`.

## Typed Action Items (#862 / C6)

C6 should introduce one canonical treatment action-item value shape unless the
implementation also introduces first-class per-item identity, direct per-item
mutation endpoints, or independent per-item audit requirements. Current repo
semantics point to the value-shape path: `action_items` is already a JSON text
column on the audited `TreatmentPlan` aggregate, and callers update action items
through the treatment-plan service boundary rather than by addressing an item
row.

The action-item shape is not a treatment-plan lifecycle state. Use a separate
typed enum for item status (the issue names `PLANNED`, `IN_PROGRESS`,
`BLOCKED`, `DONE`, and `CANCELED`) rather than reusing `TreatmentPlanStatus`,
whose `COMPLETED` terminal state and transition rules describe the plan as a
whole. Do not add item-status transition rules unless a requirement names them;
C6 only needs typed current state, and C8 can later observe changes at the same
canonical item-status field.

Keep the field contract boring and shared across REST, service commands,
persistence JSON, MCP, and docs: required `owner`, required `dueDate`, required
`status`, and optional `assignee`. If legacy rows contain useful free text such
as `description` or `action`, preserve it through one explicitly named optional
field instead of keeping arbitrary maps alive. Do not tunnel assignee, status,
or due dates through `metadata`, `rationale`, plan-level `owner`, plan-level
`dueDate`, or methodology-specific strategy bindings.

Request DTOs must put Bean Validation at the nested item boundary:
`@Valid List<...> actionItems`, `@NotBlank`/bounded `owner`,
`@NotNull dueDate`, and `@NotNull status`. The service must still reject invalid
action-item writes that bypass controller validation, using the same
`DomainValidationException`/`GlobalExceptionHandler` path as other domain
validation failures. Invalid item-status enum values should continue to flow
through Jackson enum binding and the existing invalid-enum handling in
`GlobalExceptionHandler`; do not add treatment-plan-local error envelopes.

For the JSON persistence path, extend `JacksonTextCollectionConverters` with a
typed action-item list converter instead of adding a feature-local parser. The
converter may be lenient when reading historic persisted rows, but write paths
must emit only the canonical typed JSON shape. Existing rows are known to include
legacy objects such as `{"description": ...}` from `V043` and ad hoc maps from
older API writes; either normalize those rows with a Flyway repair before strict
reads, or keep the read compatibility tightly scoped and covered by converter
tests. Do not let the compatibility reader become permission to accept new
untyped writes.

The MCP `gc_risk_governance` treatment-plan contract must change in lockstep:
replace `action_items: z.array(z.record(z.any()))` with the typed nested Zod
shape, keep recursive snake_case-to-camelCase mapping for item `due_date`, and
update adapter tests so create/update bodies preserve the canonical nested
fields. The action-item schema should not be marked opaque in `lib.js`; unlike
metadata, its keys are repo-owned contract fields.

## Categorised Reassessment Triggers (#863 / C8)

C8 is a typed trigger contract plus a transactional reassessment signal; it is
not a workflow engine, scheduler, ticketing integration, or risk recomputation
operation. Keep three concepts separate:

- Trigger configuration: `TreatmentPlan.reassessmentTriggers` becomes one
  canonical typed value list with `category` and an optional target reference.
- Change notification: treatment, asset, and control services publish immutable
  domain events after successful mutations and after old/new tracked values have
  been captured.
- Reassessment signal: the listener sets a durable `reassessmentRequiredAt`
  timestamp on affected risk rows; it does not recompute risk.

The trigger value shape should reuse the existing JSON-text-column pattern:
extend `JacksonTextCollectionConverters` with one typed converter and keep REST
DTOs, command records, the domain entity, MCP Zod, `TO_CAMEL`, and
`GOVERNANCE_FIELDS` aligned. Do not add a treatment-local `ObjectMapper`, a
parallel metadata map, or a second trigger schema for each methodology. Existing
free-text rows must migrate deterministically; do not infer entity targets from
human labels. If legacy labels must be retained, retain them as a bounded field
on the same typed value object, not as arbitrary metadata.

Optional trigger target references must resolve through
`GraphTargetResolverService` under the project id before persistence. If the
current asset/control/risk-scenario target enums do not exactly match the
reassessment trigger vocabulary, add the smallest resolver entry point there and
keep the same `targetEntityId` versus `targetIdentifier` split. Do not parse
`graphNodeId` strings, bypass project-scoped repositories, or duplicate resolver
logic in controllers, MCP, or frontend code.

Event payloads must be small, immutable value records, not JPA entities. They
must carry `projectId`, source entity type/id, trigger category, and old/new
values for every tracked field that caused the event. Use a single field-change
value shape across treatment, asset, and control events so the next tracked
field is added to a tracked-field set, not by inventing another event schema.
Do not log raw field values, raw action-item payloads, or user metadata maps.

Publisher coverage belongs at the service transaction boundary. `TreatmentPlan`
progress includes `transitionStatus` and changes in `ActionItem.status`.
Because action items are currently value objects without stable per-item
identity, a wholesale `actionItems` replacement can only support plan-level
status-diff semantics unless a separate requirement introduces item ids or
per-item mutation endpoints. Do not pretend list position is durable identity.
Asset-state events must cover `archive` and the project-scoped `update` path for
the documented risk-bearing fields; if deprecated UUID-only overloads remain
callable, they must either route through the same publisher path using the
asset's project id or be proven unreachable. Control-state events must cover
`transitionStatus` and `update` changes to `effectiveness`; only add other
control fields when they are explicitly documented as mitigation context.

The reassessment listener should mirror the existing
`RequirementService` -> `EmbeddingService.@TransactionalEventListener`
transactional shape, but not the best-effort semantics unless that tradeoff is
explicitly accepted. Reassessment is a governance signal, not a cache rebuild:
the listener should stay synchronous, DB-only, idempotent, and directly tested.
If future reliability needs outbox/retry semantics, that is an ADR-level change,
not a silent background thread.

Listener traversal must stay bounded to the named link surfaces:
`AssetLink`, `ControlLink`, `RiskScenarioLink`,
`TreatmentPlan.riskRegisterRecord`, and `TreatmentPlan.riskScenario`. Use
project-scoped repository queries, collect affected `RiskAssessmentResult` and
`RiskRegisterRecord` ids, deduplicate before writes, and update only the
reassessment timestamp. Do not issue ad hoc graph traversals, AGE queries, or
multi-hop inference beyond those explicit surfaces to make a test pass.

Persistence must add parent and audit columns in lockstep for every audited
entity that carries the signal. The minimum requirement target is
`RiskAssessmentResult.reassessmentRequiredAt`; add the matching
`RiskRegisterRecord` field only if register-record rows themselves need to be
routeable on the signal. Do not hide the signal in `decisionMetadata`,
`computedOutputs`, `uncertaintyMetadata`, action items, or the trigger list.
Flyway version lists in `MigrationSmokeTest` and
`RequirementsE2EIntegrationTest` must move with the migration.

## Cross-Cutting Layers

- Control-link target validation (#860 / C4): `ControlLinkService.create` must
  keep using `GraphTargetResolverService.validateControlTarget` as the only
  internal-vs-external dispatch point before duplicate detection and save.
  `targetEntityId` is the persisted slot for every first-class target the
  resolver currently treats as internal: `ASSET`, `RISK_SCENARIO`,
  `RISK_REGISTER_RECORD`, `RISK_ASSESSMENT_RESULT`, `TREATMENT_PLAN`,
  `METHODOLOGY_PROFILE`, `OBSERVATION`, `REQUIREMENT`, `FINDING`, and
  `EVIDENCE`. `targetIdentifier` remains the slot for `CODE`, `CONFIGURATION`,
  `OPERATIONAL_ARTIFACT`, and `EXTERNAL`.
- Substrate drift guardrail: do not reclassify `FINDING` or `EVIDENCE` as
  external just because older GC-T004 issue text names them that way. ADR-038
  promotes inbound `FINDING` link targets to modeled graph nodes, and the
  current evidence projection alignment treats `EVIDENCE` links as
  `EvidenceArtifact` UUID references. Any future target-type classification
  change belongs in `GraphTargetResolverService`, the matching graph projection
  contributor, docs/API.md, and resolver/service tests together.
- Security: `/api/v1/treatment-plans/**` stays inside the `ApiSecurityConfig`
  path matrix. In production the request must pass `IpAllowlistFilter`,
  `BearerTokenAuthFilter`, Spring authorization, and then `ActorFilter`.
  Controllers should not implement feature-local auth, actor overrides, or
  routes outside `/api/v1/**`.
- Request parsing and validation: Jackson enum binding and Bean Validation own
  DTO shape. Service-layer validation owns duplicate UIDs, project scoping,
  scenario/register consistency, status transitions, and internal-vs-external
  link target rules.
- Error envelope: use `NotFoundException`, `ConflictException`, and
  `DomainValidationException`; `GlobalExceptionHandler` and
  `shared.web.ErrorResponse` are the only HTTP error contract. Do not return ad
  hoc treatment-plan error bodies or leak raw JSON payloads in errors.
- Audit and actor provenance: Envers plus `ActorFilter`, `ActorHolder`, and
  `GroundControlRevisionListener` provide revision history and actor identity.
  Do not accept actor, reviewer, or approver identity from treatment-plan
  request bodies unless a separate workflow requirement defines that contract.
- Observability: use SLF4J lifecycle events only for material mutations and keep
  them stable and low-cardinality. Request IDs and actors come from
  `RequestLoggingFilter` and MDC; never log bearer tokens, raw request bodies,
  full action-item payloads, or full evidence payloads.
- Configuration and OS/runtime exposure: GC-T004 should not require new secrets,
  environment bindings, subprocesses, shell-outs, network clients, or CLI argv
  exposure. A future external-ticket or workflow integration must use
  `@ConfigurationProperties` with startup validation and keep secrets out of
  argv, logs, and error envelopes.
- MCP transport: Ground Control MCP write tools must continue to send requests
  through `request()`, which applies canonical snake_case to camelCase
  translation, `X-Actor: mcp-server`, backend error-envelope preservation, and
  bearer-token selection from `GROUND_CONTROL_API_TOKEN` /
  `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN`. Do not add treatment-plan-specific
  HTTP clients, caller-supplied headers, caller-supplied tokens, or ad hoc URL
  construction.
- Tests and policy: controller changes need `@WebMvcTest`; semantic rules need
  service tests; graph/link behavior needs resolver and projection tests;
  schema changes need migration smoke coverage. `make policy` remains the
  completion guardrail.

## MCP Contract Guardrails

Issue #880 is an MCP adapter contract-alignment fix, not a backend model
change. The backend `TreatmentPlanRequest` already names the authoritative
create fields: `uid`, `title`, `riskRegisterRecordId`, `riskScenarioId`,
`strategy`, `owner`, `rationale`, `dueDate`, `status`, `actionItems`, and
`reassessmentTriggers`. `UpdateTreatmentPlanRequest` accepts the mutable subset:
`title`, `riskScenarioId`, `strategy`, `owner`, `rationale`, `dueDate`,
`actionItems`, and `reassessmentTriggers`.

The MCP public surface should expose those fields in snake_case and rely on the
existing `toCamelCase` map to produce the backend DTO. Prefer
`risk_scenario_id` over a special-case `scenario_id` alias for treatment plans:
the explicit name matches the backend association, avoids conflating
scenario-owned fields with treatment-owned fields, and reuses the existing
`risk_scenario_id -> riskScenarioId` mapping. Use `due_date`, not `due_at`,
because the shared mapper already defines `due_date -> dueDate`.

Drop `description` and `metadata` from treatment-plan create/update unless the
backend DTO first grows those fields. Treatment-plan rationale is a first-class
field, not a generic description, and action payloads belong in `action_items`,
not an untyped metadata escape hatch. Keep `status` on create only if the
backend create DTO continues to accept it; status changes after creation must
stay on the existing transition action and `/status` backend sub-resource.

Adapter tests should lock the same regression class covered for the adjacent
MCP governance fixes: Zod admits every intended public field, the per-entity
allowlist excludes fields the backend does not accept, create/update send the
expected camelCase body, and transition sends only `{status}` to the status
endpoint.

## Extensibility

The extension seam is the canonical typed treatment action item, not a new
treatment-plan aggregate. If action items later need stable per-item identity,
direct per-item mutation endpoints, or independent audit history, the seam is a
child entity behind the same `owner`/`dueDate`/`status`/`assignee` contract. Do
not add separate action schemas for FAIR, NIST, ISO, controls, and assets.

Reassessment triggers should remain expressed as trigger categories plus target
references when they become machine-actionable: treatment progress,
asset-state change, control-state change, assessment refresh, or
methodology-specific trigger. The target-resolution seam belongs in
`GraphTargetResolverService` and graph projections so API, MCP, and future
analysis/sweep surfaces can reuse the same project-scoped validation.

For MCP field evolution, the extension seam is the consolidated
`gc_risk_governance` entity field registry plus the shared
snake_case/camelCase mapping. Adding the next treatment-plan DTO field should
require one schema/allowlist/mapping update and serialized-body tests, not a new
tool, a parallel DTO translator, or entity-specific request plumbing.

## Gotchas And Anti-Patterns

- Do not treat a treatment plan as satisfying asset scope or control mitigation
  linkage merely because it has free-text action items.
- Do not duplicate `AssetLink`, `ControlLink`, `RiskScenarioLink`, graph IDs,
  traceability links, or audit tables to make treatment-specific variants.
- Do not persist an internal `ControlLinkTargetType` through the
  `targetIdentifier` path to match stale issue text; use the resolver's current
  modeled-target classification.
- Do not hide methodology-specific strategy values in arbitrary action-item
  keys when the API enum or methodology profile should carry the contract.
- Do not reuse `TreatmentPlanStatus` for per-action-item status; item progress
  and plan lifecycle are different concepts.
- Do not keep `actionItems` as `List<Map<String, Object>>` in REST, command, or
  domain write paths after C6. Legacy-map compatibility belongs only at the
  persistence/read-migration boundary.
- Do not let a treatment plan reference a risk scenario outside the linked risk
  register record when the record already constrains scenarios.
- Do not add workflow-engine concepts for the existing five-state
  `TreatmentPlanStatus` lifecycle.
- Do not introduce endpoint-local exception mapping, security checks, JSON
  parsing, or logging conventions.
- Do not keep `due_at` on the MCP treatment-plan path; it bypasses the existing
  mapper and leaves backend `dueDate` null.
- Do not accept `description` as a treatment-plan alias; use `rationale`.
- Do not route `action_items`, `reassessment_triggers`, `rationale`, or
  `due_date` through `metadata`; the backend has first-class fields.
- Do not add a blanket `scenario_id` mapping if the backend association is
  `riskScenarioId`; prefer explicit `risk_scenario_id`.
- Do not transition GC-T004 to ACTIVE unless each clause is evidenced against
  the canonical artifacts, including asset/control linkage and reassessment
  trigger behavior.

## Non-Goals

- Implementing GC-T004 gaps as part of verification issue #825.
- Designing a generic workflow engine, ticketing integration, or background
  scheduler.
- Changing backend treatment-plan DTOs, persistence, graph projection, or status
  transition semantics just to compensate for an MCP adapter naming drift.
- Replacing existing asset, control, scenario, risk-register, assessment, or
  traceability aggregates.
- Defining first-class control effectiveness assessment behavior; that belongs
  to the control-evaluation requirement family.
