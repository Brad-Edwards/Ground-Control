# Multi-Framework Risk Terminology Crosswalk Preflight

Issue: #719
Requirement: GC-T012

This is architecture guardrail guidance for the risk terminology crosswalk. It
is not an implementation plan.

## Boundary

GC-T012 is a methodology-terminology requirement. It should make FAIR, NIST,
ISO, and custom risk vocabulary comparable without making their source terms
look interchangeable.

Keep these concepts separate:

- `MethodologyProfile` is the profile/version authority for methodology
  identity, input schema, output schema, and treatment strategy vocabulary.
- `RiskAssessmentResult` is the assessment snapshot. It owns the profile
  reference, methodology-specific input factors, computed outputs, confidence,
  uncertainty, evidence refs, observations, and approval state.
- `RiskScenario` owns the scoped loss-scenario statement. Its FAIR-CRST axes
  are not assessment-factor vocabulary.
- `RiskRegisterRecord` owns register governance and decision metadata.
- `TreatmentPlan` owns the selected treatment strategy and action state.
- `RiskControlMapping` owns control-to-risk relationships and optional
  methodology influence. Its influence payload is not the crosswalk itself.

The crosswalk is a profile-scoped interpretation layer over methodology terms.
It is not a new source of assessment truth and not a lossy normalized assessment
record.

## Incumbents To Reuse

- Methodology substrate: `MethodologyProfile`, `MethodologyFamily`, seeded
  FAIR / NIST / ISO / legacy profiles, `inputSchema`, `outputSchema`, and
  `treatmentStrategyVocabulary`.
- Assessment substrate: `RiskAssessmentResult`, `RiskAssessmentResultService`,
  `RiskAssessmentResultRepository`, approval-state transitions, observations,
  and evidence refs.
- Existing schema/value storage: `JacksonTextCollectionConverters` and the
  existing profile JSON column pattern. If the crosswalk becomes a repo-owned
  public contract, prefer typed value records plus a converter over opaque
  `Map<String,Object>` writes at the API boundary.
- Existing validation boundaries: request DTO Bean Validation and Jackson enum
  binding for shape, services for same-project and semantic validation, domain
  exceptions for failures.
- Existing GRC analysis contract:
  `architecture/notes/mcp-grc-analysis-tools-preflight.md`, especially
  methodology attribution, explicit `scale` / `units`, and no generic
  `risk_score`.
- Existing FAIR scenario boundary:
  `architecture/notes/fair-risk-scenario-taxonomy-preflight.md`; do not move
  assessment vocabulary into `RiskScenario`.
- Existing treatment strategy binding:
  `architecture/notes/risk-treatment-plan-preflight.md`; treatment vocabulary
  remains profile-owned and separate from assessment input/output vocabulary.
- Existing MCP adapter surface: `gc_risk_governance` for methodology profile
  and risk assessment writes, `gc_query` for reads, shared `pick`,
  `toCamelCase`, `reqArg`, `RequestError`, and per-entity allowlists.
- Existing cross-cutting concerns: `ApiPathMatrix`, `IpAllowlistFilter`,
  bearer/browser auth chains, `ActorFilter` / `ActorHolder`, Envers,
  `RequestLoggingFilter`, `GlobalExceptionHandler`, `ErrorResponse`, Flyway,
  ArchUnit, `@WebMvcTest`, and `make policy`.

## Crosswalk Contract

Use normalized concept identifiers only as classifiers. They must not replace
method-specific source terms, field paths, definitions, scales, units, or
derivation rules.

The minimum normalized concept set comes from GC-T012:

- threat source
- threat event
- vulnerability or exposure
- asset
- process or objective
- consequence or effect
- control
- likelihood or frequency
- impact or loss magnitude
- treatment

Each exposed crosswalk entry must remain profile-scoped and traceable:

- `methodologyProfileId`, `profileKey`, `family`, and `version`
- normalized concept identifier
- source vocabulary surface, such as input schema, output schema, or treatment
  vocabulary
- source field path or stable strategy key
- source term label and method-specific definition or semantics
- scale, units, and conversion rule when a value is mapped or derived
- limitations when the normalized bucket is only approximate

If persisted, the default owner should be `MethodologyProfile`, not
`RiskAssessmentResult`, `RiskScenario`, `RiskRegisterRecord`, or
`TreatmentPlan`. A profile-owned JSON value list is the smallest fit when the
crosswalk is mostly read with the profile. A first-class project-scoped table
is justified only if the implementation needs cross-profile search, lifecycle,
or high-cardinality queries that JSON cannot serve cleanly. Either way, profile
version must be part of the identity so profile updates do not rewrite the
meaning of historical assessment outputs.

Assessment responses and analysis results must continue to carry their original
`methodologyProfileId` / `profileKey` / `family` / `version`. A normalized
crosswalk view may group outputs for comparison, but it must not return a
method-stripped `likelihood`, `impact`, `threat`, or `score` field as if FAIR,
NIST, and ISO had identical semantics.

## Cross-Cutting Layers

- Authorization: new or changed HTTP routes stay under `/api/v1/**` unless an
  ADR changes the path matrix. The bearer path passes `IpAllowlistFilter`,
  `BearerTokenAuthFilter`, `ApiPathMatrix`, then `ActorFilter`; browser traffic
  uses the browser/session chain with the same matrix.
- Project isolation: resolve one project at the controller boundary through
  `ProjectService`; repositories and services must use project-scoped lookups
  for profiles and assessment rows. Do not infer a global profile by key.
- Request validation: DTOs own bounded string, enum, UUID, and required-field
  shape. Services own crosswalk semantic validation: profile exists in project,
  profile status handling, valid normalized concept, valid source surface, field
  path exists in the relevant profile vocabulary, no duplicate concept/path
  entries, and no cross-profile fallback.
- Methodology schema validation: the repo has profile schemas but no general
  JSON Schema validator. Do not copy schema parsing into controllers or MCP
  handlers. If validation grows beyond simple profile-vocabulary membership,
  put a reusable validator behind the domain service boundary.
- Error envelope: throw `DomainValidationException`, `ConflictException`, or
  `NotFoundException`; let `GlobalExceptionHandler` serialize `ErrorResponse`.
  Error detail may name stable fields, concept ids, profile keys, and field
  paths, but must not echo raw assessment inputs, computed outputs, evidence
  payloads, bearer tokens, or stack traces.
- Logging and audit: use SLF4J and existing MDC keys from
  `RequestLoggingFilter` / `ActorFilter`. Do not log full crosswalk payloads,
  assessment maps, evidence refs with sensitive content, headers, or tokens.
  Persisted profile/crosswalk changes should remain Envers-audited through the
  owning aggregate.
- Persistence: if schema changes are needed, add Flyway parent and audit-table
  changes together, project indexes for primary reads, migration-smoke coverage,
  and `RequirementsE2EIntegrationTest` version-list updates. Use existing JSON
  converters or normal JPA entities; do not add feature-local `ObjectMapper`
  parsing.
- API/MCP/frontend mirrors: API-visible normalized concept enums or constants
  must follow ADR-034. Update Java, MCP Zod/constants, frontend types, docs, and
  policy inventory together when the concept set changes.
- Graph: do not create a graph node for every vocabulary term unless a graph
  requirement needs traversal. If graph properties are added to methodology or
  assessment nodes, register AGE property keys and test them per ADR-032.
- Runtime/config/OS exposure: GC-T012 should not need new env vars, tokens,
  subprocesses, shell-outs, or process-argv secrets. If configurability appears,
  use `@ConfigurationProperties` with startup validation.

## Extensibility

The extension seam is the normalized concept vocabulary plus
profile-version-scoped mappings to source terms. Adding a methodology should
require adding or updating a `MethodologyProfile` and its crosswalk entries, not
editing assessment storage, graph clients, auth, error handling, or MCP
transport.

The likely next variation is a new methodology family or a second version of an
existing profile. Keep the crosswalk keyed by profile identity and field path so
version-specific differences are data, not branches across controllers or MCP
handlers.

If a future requirement needs conversion between scales, the conversion rule
must be explicit and method-labeled. Do not silently compare FAIR annualized
loss, NIST ordinal likelihood, ISO organization-defined risk value, and control
effectiveness ratings as one shared numeric axis.

## Gotchas And Anti-Patterns

- Do not add a single `threat`, `likelihood`, `impact`, `control`, or
  `treatment` field to assessment outputs that hides the source methodology.
- Do not rename existing FAIR/NIST/ISO profile fields into normalized names in
  `inputFactors` or `computedOutputs`.
- Do not parse prose descriptions inside `inputSchema` / `outputSchema` as the
  machine-readable crosswalk. Store the mapping explicitly.
- Do not put the crosswalk in `RiskRegisterRecord.decisionMetadata`,
  `RiskAssessmentResult.computedOutputs`, `RiskScenario` text fields,
  `TreatmentPlan.actionItems`, or risk-control mapping influence payloads.
- Do not reuse `MethodologyInfluenceValidator` as a general crosswalk validator;
  it validates C4 risk-control influence against `inputSchema`.
- Do not create one MCP tool per methodology or a generic write passthrough.
- Do not duplicate project scoping, JSON parsing, exception envelopes, auth
  filters, audit writers, graph clients, or schema validators.
- Do not treat `CUSTOM` profiles as exempt from traceability. Custom mappings
  still need profile id, profile key, version, and source field paths.

## Non-Goals

- No FAIR calculation engine, Monte Carlo simulation, NIST risk engine, or ISO
  risk matrix implementation.
- No redesign of `RiskScenario`, `RiskAssessmentResult`,
  `RiskRegisterRecord`, `TreatmentPlan`, `RiskControlMapping`, findings,
  evidence, audits, or graph projection ownership.
- No new authorization model, secret storage, workflow automation, GitHub side
  effect, external standard-ingest job, or local file scanner.
- No OpenAPI/codegen migration or generic schema framework solely for GC-T012.

## Whole-Repo Surfaces In Scope

- Backend: `api/riskscenarios`, `domain/riskscenarios`,
  `domain/riskcontrol`, `domain/grcanalysis`, `domain/graph`,
  `domain/exception`, `shared/persistence`, `shared/security`,
  `shared/logging`, and `shared/web`.
- MCP: `mcp/ground-control/gc-risk-governance.js`,
  `mcp/ground-control/gc-query.js`, `mcp/ground-control/lib.js`,
  `mcp/ground-control/index.js`, and matching adapter tests when MCP fields
  change.
- Frontend: `frontend/src/types/api.ts` and any profile or assessment views
  that display normalized concepts.
- Docs/policy: `docs/API.md`, `docs/architecture/ARCHITECTURE.md`,
  `docs/CODING_STANDARDS.md`, ADR-032, ADR-034, ADR-035, ADR-052, this note,
  `.ground-control.yaml`, `.gc/plan-rules.md`, `tools/policy/checks.py`, and
  `Makefile`.
- Runtime: `application.yml`, `SecurityProperties`, `ApiPathMatrix`, Logback
  MDC configuration, Flyway migrations, Envers revision actor provenance, AGE
  property-key allowlists, and MCP env token loading.
