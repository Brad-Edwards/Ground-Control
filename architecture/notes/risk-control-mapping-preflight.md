# Risk-Control Mapping Preflight

Implementation issue: #258
Verification issue: #824
Requirement: GC-T003

This is architecture guardrail guidance for the implementation that verifies or
completes GC-T003. It is not an implementation plan.

## Boundary

Risk-control mapping is contextual mitigation evidence between existing risk and
control concepts. It must stay separate from:

- `Control`, which owns catalog-level control identity, objective, function,
  implementation scope, methodology factors, status, and effectiveness summary.
- `RiskScenario` and `RiskRegisterRecord`, which own scenario and governance
  scope, not control-specific reduction math.
- `RiskAssessmentResult`, which owns method-specific input factors, computed
  outputs, confidence, uncertainty, evidence refs, observations, and approval.
- `AssetLink`, `ControlLink`, `RiskScenarioLink`, and graph projections, which
  provide project-scoped traversal and internal target identity.

Do not treat an ordinary link row as a complete GC-T003 mapping unless the
contextual mapping attributes are represented somewhere explicit: control
objective, role, scope, asset or boundary context, and method-specific influence.
Also do not push those attributes into `RiskAssessmentResult` as if the
assessment itself were the mapping; assessments consume mapped controls and
evidence, they are not the control-to-risk relationship.

## Incumbents To Reuse

- REST shape: thin `api/*Controller` classes, request/response records, `@Valid`,
  and `ProjectService.requireProjectId`.
- Domain shape: `domain/controls`, `domain/riskscenarios`, command records,
  repositories, service-owned semantic validation, and Envers-audited JPA
  entities extending `BaseEntity`.
- Project-scoped target validation: extend or reuse `GraphTargetResolverService`
  for internal targets. `RiskScenarioLinkService` and `ControlLinkService` both
  route internal targets through it; any canonical GC-T003 mapping owner or
  projection must preserve that same same-project validation boundary.
- Link persistence: reuse the existing internal-target pattern
  (`targetEntityId` for first-class entities, `targetIdentifier` only for
  external or not-yet-modeled artifacts), unique constraints, and reverse lookup
  repository queries.
- Methodology shape: use `MethodologyProfile.inputSchema` / `outputSchema` and
  `RiskAssessmentResult.inputFactors` / `computedOutputs` for FAIR, NIST, ISO,
  and custom assessment values. Store arbitrary JSON maps with
  `JacksonTextCollectionConverters`; do not add feature-local JSON parsers.
- Evidence and observations: reuse `Observation` as the storage entity and
  `RiskAssessmentResult.evidenceRefs` as the assessment-side edge. Do not create
  a parallel `Observation` schema for control mappings. However, the existing
  `risk_assessment_result_observation` join only records which observations an
  assessment includes; it carries no mapping provenance. If GC-T003 keeps the
  "observations and evidence anchored on a mapping" phrasing, the implementation
  must add a mapping-owned provenance edge (e.g., `mapping_observation` /
  `mapping_evidence`, or an equivalent graph projection) on top of the existing
  storage so per-mapping retraction, re-scoping, and validation are possible.
  Alternatively, narrow C8 so observations/evidence feed assessments without
  going through a mapping. See `risk-control-mapping-verification.md` §C8 for
  the three-decision breakdown.
- GC-I013 dependency: `ControlEffectivenessAssessment` is now the first-class
  control evaluation aggregate, with project-scoped service/repository/API/MCP
  surfaces and `operatingEffectiveness` as the stable risk-consumption input.
  GC-T003 must consume those rows through a project-scoped, as-of-aware service
  path; do not use `VerificationResult`, `ControlStatus`, or catalog-level
  control summary fields as substitutes. Current `RiskAssessmentResult` rows do
  not yet record which control effectiveness assessments influenced them, so the
  implementation still needs an explicit feed/provenance edge or snapshot.
- Graph: update `GraphEntityType`, `GraphIds`, and the relevant
  `GraphProjectionContributor` path. Do not add controller/service direct AGE
  writes or feature-specific graph endpoints.
- API/MCP/frontend enum mirrors: if new control/risk enum values or DTO fields
  become API-visible, update the MCP constants and frontend API types; consider
  extending ADR-034's policy-inventory pattern rather than adding another manual
  mirror.

## Cross-Cutting Layers

- Security: all `/api/v1/**` mapping endpoints stay behind `ApiSecurityConfig`,
  `IpAllowlistFilter`, and `BearerTokenAuthFilter`. Do not add a controller-local
  auth model, token store, actor override, or route outside the path matrix.
- Request parsing and validation: Jackson enum binding and Bean Validation own
  DTO shape. Semantic rules such as same-project target ownership, duplicate
  mappings, internal-vs-external target shape, valid method influence fields, and
  scenario/register consistency belong in services.
- Methodology validation: `MethodologyProfile.inputSchema` / `outputSchema`
  provide the profile boundary, but there is not yet one repo-wide JSON Schema
  validator service for every methodology payload. If GC-T003 validates
  influence factors, put that behind one reusable service keyed by methodology
  profile and payload role; do not branch per methodology in controllers or add a
  mapping-local parser.
- Error envelope: throw existing domain exceptions
  (`NotFoundException`, `ConflictException`, `DomainValidationException`) and let
  `GlobalExceptionHandler` / `ErrorResponse` serialize them. Do not create a
  control-mapping exception hierarchy or echo raw JSON payloads in errors.
- Audit and actor provenance: audited entities rely on Envers, `ActorFilter`,
  `ActorHolder`, and `GroundControlRevisionListener`. Use the authenticated
  principal from the shared security chain; do not read actor identity from a
  mapping request body or trusted header.
- Observability: lifecycle logs should be SLF4J structured events with stable IDs
  and no tokens, raw request bodies, or full evidence payloads. Request and actor
  context come from `RequestLoggingFilter`, MDC, and `ActorFilter`.
- Config and OS/runtime exposure: GC-T003 should not require new secrets,
  environment bindings, subprocesses, shell-outs, or network clients. If a later
  control-evaluation adapter does, it must use `@ConfigurationProperties` with
  startup validation, avoid tokens in process argv, and keep secret values out of
  logs and error envelopes.
- Persistence and migrations: Flyway is the schema source of truth. Any new
  audited entity needs an audit table, project scope, indexes for reverse lookups,
  and the migration companion updates required by `.gc/plan-rules.md`.
- Tests and policy: controller additions need `@WebMvcTest`; service rules need
  unit tests; graph and target resolver changes need focused tests; migrations
  need smoke-list updates. Run `make policy` before declaring repo work complete.

## Extensibility

The extension seam is a method-aware mapping context keyed by project, control
or scoped implementation, risk scenario or register record, and asset or
boundary context. Method-specific influence should be parameterized by
methodology family/profile, factor key, payload role, and effective/as-of date,
and stored as a structured payload validated against the relevant profile
semantics, so adding another methodology or influence dimension does not require
another link table or a second assessment model.

The read side for "unmapped scenarios/records" and "controls not mapped to any
relevant scenario" should be queryable by project and, where applicable, by
asset/boundary and target type. Avoid one-off report logic that cannot be reused
by API, MCP, graph, and policy/live verification surfaces.

## Gotchas And Anti-Patterns

- Do not conflate control role with `Control.controlFunction` unless the
  mapping-specific role truly matches the catalog-level function; role in
  GC-T003 can be contextual to a scenario.
- Do not put likelihood, consequence, FAIR frequency, or FAIR magnitude reduction
  directly on `Control` as global truth. Those values are scenario, asset, and
  methodology dependent.
- Do not represent scoped control implementations only as free text when the
  relevant asset or boundary exists as `OperationalAsset`.
- Do not rely on both `ControlLink` and `RiskScenarioLink` independently and call
  that bidirectional mapping if their metadata or validation can diverge. Pick
  one canonical relationship owner or keep the two projections mechanically
  consistent.
- Do not use raw identifiers for first-class controls, risk scenarios, risk
  register records, risk assessments, observations, or assets.
- Do not introduce a duplicate JSON schema system, exception hierarchy, audit
  writer, auth guard, logging channel, graph materializer, or workflow engine.
- Do not transition GC-T003 to ACTIVE solely because partial link CRUD exists;
  the methodology influence, unmapped coverage, observations/evidence feed, and
  GC-I013 control-effectiveness feed clauses must be explicitly accounted for.

## Non-Goals

- No expansion of GC-I001 control catalog or GC-I013 control effectiveness
  assessment behavior inside GC-T003 unless those requirements are explicitly
  taken on.
- No replacement of `RiskScenarioLink`, `ControlLink`, `RiskAssessmentResult`,
  `Observation`, or `MethodologyProfile` with a generic all-purpose relation
  table.
- No new external integration, background worker, risk calculation engine, or
  automatic residual-risk computation unless a separate requirement defines it.
- No change to ADR-026 security, ADR-033 actor provenance, ADR-034 enum mirror
  policy, or repo workflow rules.
