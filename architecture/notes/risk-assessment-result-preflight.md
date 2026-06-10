# Risk Assessment Result Preflight

Issue: #826
Implementation issue: #731
Requirement: GC-T010

This is architecture guardrail guidance for verification or follow-up
implementation of GC-T010. It is not an implementation plan.

## Boundary

`RiskAssessmentResult` is the durable, methodology-aware assessment snapshot for
a risk scenario at a point in time. It owns assessment input factors,
assumptions, analyst or agent identity, observation date, time horizon,
confidence or uncertainty metadata, evidence references, observation links,
computed outputs, and approval state.

It must stay separate from:

- `RiskScenario`, which owns the scenario statement and semantic risk scope.
- `RiskRegisterRecord`, which owns register/governance grouping, ownership,
  review cadence, and decision metadata.
- `TreatmentPlan`, which owns treatment strategy, actions, due dates, and
  reassessment triggers.
- `MethodologyProfile`, which owns reusable methodology identity and input/output
  schema definitions, not individual result values.
- `Observation`, `AssetLink`, `ControlLink`, `RiskScenarioLink`, and graph
  projections, which provide evidence, operational context, mitigation context,
  and project-scoped traversal.

Do not collapse multiple assessments for the same scenario into mutable score
fields on `RiskScenario` or `RiskRegisterRecord`. A new methodology, assessment
date, or analysis run must be represented by a distinct `RiskAssessmentResult`
row unless the caller is explicitly editing that same result.

## Incumbents To Reuse

- REST shape: `RiskAssessmentResultController`, request/response records,
  `@Valid`, Jackson enum binding, and
  `ProjectService.resolveProjectId/requireProjectId`.
- Domain shape: `RiskAssessmentResult`, `RiskAssessmentResultService`, command
  records, `RiskAssessmentResultRepository`, and
  `RiskAssessmentApprovalStatus.canTransitionTo`.
- Methodology catalog: `MethodologyProfile`, `MethodologyProfileService`,
  seeded FAIR / NIST / ISO / legacy profiles, and the existing
  `(project_id, profile_key, version)` uniqueness contract.
- JSON text columns: `JacksonTextCollectionConverters` for input factors,
  uncertainty metadata, computed outputs, and evidence refs. Do not add
  feature-local JSON string parsing or a second object-mapping convention.
- Evidence: use the existing `risk_assessment_result_observation` join for
  first-class observations and `evidenceRefs` only for external or
  not-yet-modeled evidence identifiers.
- Project scoping: resolve scenarios, methodology profiles, risk register
  records, and observations through project-scoped repository methods in the
  service layer.
- Graph: keep result, scenario, methodology, and observation traversal in
  `RiskGraphProjectionContributor`, `GraphEntityType`, and `GraphIds`; do not
  write AGE or graph rows from controllers or services.
- Audit and persistence: audited entities extend `BaseEntity`, use Envers,
  Flyway migrations, audit-table parity, project scope, indexes for primary
  read paths, and `AuditRetentionJob` updates when new audit tables are added.
- MCP adapter shape: `gc_risk_governance` in
  `mcp/ground-control/index.js`, the per-entity `GOVERNANCE_FIELDS`
  allowlist, `pick`, `toCamelCase`, `addAuthorizationHeader`, `RequestError`,
  and `validateGovernanceStatus` in `mcp/ground-control/lib.js`. The MCP
  contract for `entity=risk_assessment_result` must mirror the backend
  create/update DTO fields for this aggregate, not the generic register-record
  or scenario fields.
- Enum mirrors: `RiskAssessmentApprovalStatus` is the backend authority for
  approval-state transitions. MCP `RISK_ASSESSMENT_APPROVAL_STATUSES` and any
  future frontend union/constant for this enum are mirrors; if the enum changes,
  update every mirror and add a policy inventory row or focused mirror test so
  drift is not caught only by a runtime 422.

## Cross-Cutting Layers

- Security: `/api/v1/risk-assessment-results/**` stays inside the shared
  `/api/v1/**` path matrix. With security enabled, bearer traffic must pass
  `IpAllowlistFilter`, `BearerTokenAuthFilter`, Spring authorization, and then
  `ActorFilter`; browser-session traffic must pass the browser chain with the
  same `ApiPathMatrix`. Do not add endpoint-local auth, actor override fields,
  or routes outside the matrix.
- Request parsing and validation: DTOs own shape validation such as required
  UUIDs and enum parsing. Service validation owns same-project checks,
  scenario/register consistency, approval-state transitions, and any
  result-level semantic checks.
- Methodology schema validation: profile `inputSchema` and `outputSchema` are
  stored today, but the backend has no canonical JSON Schema validation library
  or service. If GC-T010 verification treats schema validation as out of scope,
  document that explicitly. If implementation adds validation, introduce one
  reusable methodology-result validation component behind the service boundary,
  backed by one dependency and one error-detail shape; do not duplicate schema
  checks in controllers, DTOs, migrations, or per-methodology branches.
- Error envelope: use `NotFoundException`, `ConflictException`, and
  `DomainValidationException`; `GlobalExceptionHandler` and
  `shared.web.ErrorResponse` are the only HTTP error contract. Validation detail
  may name failing fields or schema paths, but must not echo raw assessment
  payloads.
- Audit and actor provenance: Envers plus `ActorFilter`, `ActorHolder`, and
  `GroundControlRevisionListener` provide revision actor history. The
  `analystIdentity` field is domain provenance supplied by the caller; it is not
  an authentication mechanism and must not replace the authenticated audit actor.
- Observability: request and actor context come from `RequestLoggingFilter`,
  `ActorFilter`, and MDC. Lifecycle logs should use stable IDs and low-cardinality
  fields only; never log bearer tokens, full input factors, full evidence refs,
  assumptions, uncertainty metadata, or computed outputs.
- Configuration and OS/runtime exposure: GC-T010 should not require new
  environment variables, secrets, subprocesses, shell-outs, external network
  calls, or CLI argv. Any future calculation or validation adapter that does
  must use `@ConfigurationProperties` with startup validation and keep secrets
  out of process argv, logs, and error envelopes.
- MCP transport: Ground Control MCP write tools must continue to send requests
  through `request()`, which applies the canonical snake_case to camelCase
  translation, `X-Actor: mcp-server`, backend error-envelope preservation, and
  bearer-token selection from `GROUND_CONTROL_API_TOKEN` /
  `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN`. Do not add per-tool HTTP clients,
  caller-supplied headers, caller-supplied tokens, or ad hoc URL construction
  for assessment results.
- Opaque value-bag keys: `inputFactors`, `uncertaintyMetadata`, and
  `computedOutputs` are methodology-defined maps. Transport mappers may rename
  the top-level DTO fields (`input_factors` -> `inputFactors`) but must preserve
  nested keys exactly (for example FAIR keys such as `threat_event_frequency`).
  If MCP handles these fields, include both snake_case and camelCase names in
  the shared opaque-value-key guard instead of adding entity-local map copying or
  letting `toCamelCase` recurse through methodology payloads.
- Tests and policy: controller changes need `@WebMvcTest`; semantic rules need
  service tests; approval-state rules need state-machine tests; graph/evidence
  behavior needs projection and resolver coverage; schema changes need migration
  smoke coverage. `make policy` remains the repo-native completion guardrail.

## MCP Contract Guardrails

Issue #878 is a contract-alignment fix at the MCP boundary. The backend
`RiskAssessmentResultRequest` already names the authoritative create fields:
`riskScenarioId`, `riskRegisterRecordId`, `methodologyProfileId`,
`analystIdentity`, `assumptions`, `inputFactors`, `observationDate`,
`assessmentAt`, `timeHorizon`, `confidence`, `uncertaintyMetadata`,
`computedOutputs`, `evidenceRefs`, `notes`, and `observationIds`.

The MCP surface should expose those fields in snake_case and rely on the
existing `toCamelCase` map to produce the backend DTO. Prefer
`risk_scenario_id` over adding a special-case `scenario_id` alias for assessment
results: the explicit name matches the backend association, avoids conflating
scenario-owned fields with assessment-owned fields, and reuses the existing
`risk_scenario_id -> riskScenarioId` mapping. `scenario_id` may remain valid for
entities whose backend DTOs actually own `scenarioId`, such as register records
or treatment plans; do not force a repo-wide rename that would change those
contracts without a separate compatibility decision.

Drop `uid`, `title`, `description`, `quantitative_value`, and
`qualitative_value` from the assessment-result allowlist and schema unless the
backend DTO first grows those fields. They currently describe scenario,
register-record, or score-summary concepts, not the durable methodology result.
Unknown user fields should continue to be rejected by Zod or omitted by the
per-entity allowlist before dispatch; they must not be tunneled through
`metadata` to compensate for a mismatched typed contract.

## Extensibility

The primary extension seam is methodology-result validation keyed by
`MethodologyProfile` and by payload role (`inputFactors` vs `computedOutputs`).
If schema validation becomes in scope, keep it profile-driven so adding another
methodology or version requires changing profile data and tests, not adding
controller branches or new result columns.

For MCP field evolution, the extension seam is the per-entity field registry
plus the shared snake_case/camelCase mapping. Adding the next assessment-result
field should require one allowlist/schema/mapping update and tests for the
serialized backend body, not a new tool, a parallel DTO translator, or
entity-specific request logic.

The second extension seam is evidence identity: modeled observations should stay
as project-scoped `Observation` links, while unmodeled external evidence stays
as identifiers until a first-class evidence aggregate exists. When evidence
becomes first-class, extend the existing target-resolution and graph-projection
paths instead of adding assessment-only evidence tables.

## Gotchas And Anti-Patterns

- Do not treat a risk scenario, register record, treatment plan, control mapping,
  or threat model as the assessment result.
- Do not add a uniqueness constraint that permits only one assessment per
  scenario, methodology, or day; GC-T010 requires coexistence across
  methodologies and time points.
- Do not validate project ownership in controllers or rely on client convention
  for same-project scenario/profile/observation linkage.
- Do not store method-specific outputs in `RiskRegisterRecord.decisionMetadata`
  or `RiskScenario` fields.
- Do not invent a duplicate JSON schema dialect, exception hierarchy, audit
  writer, auth guard, logging channel, graph materializer, or workflow engine.
- Do not keep generic `uid` / `title` / `description` / qualitative or
  quantitative score fields on the MCP assessment-result create/update path
  unless the backend assessment-result DTO accepts them.
- Do not route typed assessment fields through `metadata`; the backend has
  first-class fields for methodology inputs, outputs, evidence, timing,
  assumptions, and analyst identity.
- Do not recursively rename keys inside methodology-defined value bags; schema
  matching depends on the persisted keys staying exactly as supplied by the
  methodology profile and caller.
- Do not add a `scenario_id` mapping as a blanket workaround if the entity's
  real backend association is `riskScenarioId`; prefer the explicit
  `risk_scenario_id` field for assessment results.
- Do not mark schema-validation behavior as satisfied merely because
  `MethodologyProfile.inputSchema` and `outputSchema` are persisted.
- Do not let `analystIdentity` spoof or override the authenticated audit actor.

## Non-Goals

- No implementation of missing GC-T010 gaps in the verification issue.
- No risk calculation engine, Monte Carlo runner, control-effectiveness model,
  or automatic residual-risk computation.
- No implementation of GC-T011 / GC-T013 / GC-T014 methodology-fidelity schema
  validation unless those requirements are explicitly pulled into scope.
- No replacement of `RiskScenario`, `RiskRegisterRecord`, `TreatmentPlan`,
  `Observation`, `MethodologyProfile`, graph projection, or traceability
  aggregates with a generic all-purpose risk record.
