# FAIR Risk Scenario Taxonomy Preflight

Issue: #720
Requirement: GC-T013

This is architecture guardrail guidance for FAIR-aligned scenario scoping. It
is not an implementation plan.

## Boundary

`RiskScenario` remains the canonical scoped loss-scenario statement. GC-T013
should extend or validate that aggregate's scenario-scoping semantics; it should
not introduce a parallel FAIR scenario table, a separate risk-taxonomy subsystem,
or a duplicate DTO family that bypasses the existing risk-scenario service.

The FAIR sentence form:

`[threat] impacts [asset] via [method], causing [effect(s)]`

must be a projection of explicit components, not an independently mutable
sentence that can drift from the stored fields. Existing fields already cover
much of the shape:

- `threatSource`/`threatEvent`: threat actor/source and event framing.
- `affectedObject`: narrative affected asset, boundary, system, or objective.
- `vulnerability`: optional contributing weakness, not the scenario itself.
- `consequence`: effect or loss outcome.
- `timeHorizon`: analysis period.
- `RiskScenarioLink` to `ASSET`, `OBSERVATION`, `FINDING`, `AUDIT_RECORD`,
  `THREAT_MODEL`, `CONTROL`, `METHODOLOGY_PROFILE`, and related risk artifacts:
  graph-native context and provenance.

If GC-T013 needs a first-class `method` component or multi-effect expression,
that belongs on the risk-scenario statement surface or as a derived field from
the existing statement fields. Do not overload `vulnerability`, `metadata`,
`RiskAssessmentResult.inputFactors`, or `RiskRegisterRecord.decisionMetadata`
to carry scenario-statement components.

## Incumbents To Reuse

- REST/API shape: `RiskScenarioController`, `RiskScenarioRequest`,
  `UpdateRiskScenarioRequest`, `RiskScenarioResponse`, `@Valid`, and
  `ProjectService.resolveProjectId`/`requireProjectId`.
- Domain owner: `RiskScenarioService`, `RiskScenario`, command records, and
  `RiskScenarioRepository` project-scoped queries.
- Links and target validation: `RiskScenarioLink`, `RiskScenarioLinkService`,
  and `GraphTargetResolverService.validateRiskScenarioTarget` for same-project
  internal targets. Use links for existing assets, findings, audits, controls,
  observations, threat models, evidence, and methodology profiles.
- Methodology context: `MethodologyProfile` / `MethodologyFamily.FAIR` already
  identify FAIR assessment vocabulary. Keep methodology-specific assessment
  inputs and outputs in `RiskAssessmentResult`, not on the scenario statement.
- Risk governance separation: `RiskRegisterRecord` owns register grouping and
  decisions; `RiskAssessmentResult` owns assessment values; `TreatmentPlan`
  owns treatment strategy and actions; `RiskControlMapping` owns control-to-risk
  relationships.
- Threat-model boundary: ADR-024 keeps threat modeling separate from risk
  scenarios. Do not turn threat-model entries into the FAIR scenario taxonomy.
- Graph surface: `RiskGraphProjectionContributor`, `GraphEntityType`, and
  `GraphIds` are the graph path. Do not add direct AGE writes, feature-specific
  graph endpoints, or controller-side graph mutation.
- MCP adapters: `gc-risk-scenario.js`, `gc-risk-governance.js`,
  and `mcp/ground-control/lib.js`. Update `TO_CAMEL` mappings, body allowlists, `pick`, and
  `reqArg` configurations. Expose any new backend DTO fields there deliberately; do not tunnel
  them through `metadata`.
- Frontend mirrors: `frontend/src/types/api.ts` and graph tooltip rendering
  mirror API-visible enum/type surfaces. Update mirrors only when the backend
  contract changes.
- Persistence conventions: Flyway migrations, Envers audit-table parity,
  project scope, indexes for query paths, and `MigrationSmokeTest` coverage if
  schema changes.

## Cross-Cutting Gates

- Authorization: any new or changed HTTP route must stay under the existing
  `/api/v1/**` path matrix. Do not add controller-local auth, public scenario
  endpoints, or privileged carve-outs.
- Browser/session security: SPA writes continue through the browser security
  chain and CSRF handling; bearer clients continue through the bearer chain.
  GC-T013 should not need new cookies, token schemes, or auth filters.
- Project isolation: every by-id read, update, delete, link, and validation path
  must use the resolved project id and project-scoped repository methods. Do not
  reintroduce global UUID lookups or use deprecated projectless overloads.
- Request validation: DTO Bean Validation owns shape checks; service-layer
  semantic validation owns scenario-quality rules, same-project checks, duplicate
  checks, and update invariants. Update paths must continue rejecting blank
  values for required-on-create fields when those fields are present.
- Error envelope: throw `DomainValidationException`, `ConflictException`, or
  `NotFoundException` so `GlobalExceptionHandler` emits `ErrorResponse`. Do not
  add risk-specific exception mappers or ad hoc response bodies. Validation
  detail should identify fields/reasons without echoing long raw scenario text.
- Audit and logging: Envers, `ActorFilter` / `ActorHolder`, MDC, and service
  lifecycle logs remain the audit/observability path. Do not log raw request
  bodies, bearer tokens, generated scenario prose, or analyst notes.
- Configuration and OS exposure: no new env vars, secrets, subprocesses,
  shell-outs, or argv-bearing tokens are needed. If future configurability is
  unavoidable, use `@ConfigurationProperties` with startup validation and keep
  secrets out of logs and process arguments.
- Enum/mirror contracts: if a new API-visible enum is introduced for scenario
  quality or classification, treat the Java enum as authoritative and update MCP
  and frontend mirrors in the same change per ADR-034.
- Tests and policy: use `@WebMvcTest` slices for controller contract coverage,
  service unit tests for semantic validation, focused MCP adapter tests for
  schema/body allowlist drift, frontend type/render tests when UI types change,
  migration smoke tests when persistence changes, then run `make policy`.

## Scenario Quality Guardrails

GC-T013's distinction between true loss scenarios and pre-scenario material is a
domain invariant, not just UI copy. A record is not a risk scenario unless the
write path can represent threat, affected asset/scope, method or event path,
effect or loss outcome, and time horizon.

Use existing entities for non-scenarios:

- Control deficiencies belong to `RiskControlMapping`, controls, findings, or
  observations until they are framed as loss scenarios.
- Vulnerabilities are contributing conditions or external references; link them
  through `RiskScenarioLinkTargetType.VULNERABILITY` or model them as findings
  when first-class evidence exists. Do not make a vulnerability-only statement
  an analyzable scenario.
- Audit findings belong to the audit/finding surfaces and may link to scenarios
  once a scenario exists. Do not use audit status or audit scope as a scenario
  substitute.
- Generic concerns remain observations, findings, notes, or external references
  until they include analyzable loss-scenario components.

## Extensibility

The extension seam is the explicit component contract on `RiskScenario` plus a
small formatting/validation surface that can produce the FAIR sentence form from
stored components. Keep that seam parameterized by methodology family/profile
only when a second real grammar or validation vocabulary exists; otherwise three
clear lines near the DTO/service mapping beat a premature strategy framework.

Graph-native context should continue to extend through `RiskScenarioLink` target
types and `GraphTargetResolverService`, not through nullable foreign keys for
every possible scenario component. If assets need to become authoritative rather
than narrative, the authoritative reference is a project-scoped asset link; the
free-text field remains analyst context.

## Anti-Patterns

- Creating a second FAIR scenario aggregate beside `RiskScenario`.
- Storing the formatted sentence as a mutable source of truth independent of the
  component fields.
- Treating `vulnerability`, `Finding`, `Audit`, or `Control` records as scenarios
  without threat, asset/scope, method/event path, effect, and time horizon.
- Adding methodology assessment factors, likelihood, ALE, residual risk, or
  treatment state to `RiskScenario`.
- Putting scenario-classification data into generic `metadata` fields or
  `RiskAssessmentResult.inputFactors` because it is convenient.
- Duplicating link validation, project checks, JSON parsing, error envelopes, or
  MCP transport helpers.
- Adding an L2/L3 formal-methods or workflow engine for bounded scenario-quality
  validation unless the domain grows real stateful invariants.

## Non-Goals

- No FAIR calculation engine, FAIR-CAM/MAM computation, Monte Carlo simulation,
  loss-exceedance curve, or quantified assessment output.
- No redesign of risk register, assessment result, treatment plan, risk-control
  mapping, finding, audit, threat-model, or graph-projection ownership.
- No new auth model, config namespace, secrets, external network integration, or
  GitHub/workflow side effect.
- No OpenAPI/codegen migration or generic DTO-schema framework as part of
  GC-T013.
