# FAIR-CAM Control Analytics Preflight

Issue: #746
Requirement: GC-I017

This note records architecture guardrails for FAIR-CAM-aligned control
analytics. It is not an implementation plan.

## Boundary

GC-I017 is a read-side, methodology-attributed control analytics capability.
It must sit on the existing control, testing, effectiveness, mapping, evidence,
and GRC analysis substrates. It must not introduce a second control model, a
parallel FAIR risk engine, or an MCP-local analytics engine.

Keep these concepts separate:

- Open FAIR quantitative risk analysis (`fair_quantitative`) estimates risk
  factors and loss outputs from `RiskAssessmentResult` rows. FAIR-CAM control
  analytics explains how controls influence those factors or decision quality.
- `RiskControlMapping` is the canonical owner of a control's contextual
  relationship to a risk scenario, risk register record, or threat model,
  including control role, scope, methodology influence, observations, and
  evidence refs.
- `ControlTest` is a per-execution evidence row. It is not a capability score.
- `ControlEffectivenessAssessment` is a per-control rating conclusion. Its
  `operatingEffectiveness` is useful evidence, but it must not be collapsed into
  FAIR-CAM capability, coverage, operational performance, or residual risk.
- `Control.status` and `Control.effectiveness` are catalog/projection fields.
  They are not proof that the control affects frequency, magnitude,
  reliability, or decision alignment.
- FAIR-CAM's Loss Event Control, Variance Management Control, and Decision
  Support Control domains are methodology labels in the analysis result. They
  must not be substituted with catalog-level `ControlFunction` or
  mapping-level `MappingControlRole` unless the response also preserves which
  substrate drove the classification.
- Evidence absence is a first-class limitation. A control with no relevant
  `ControlTest` or `ControlEffectivenessAssessment` evidence can still appear
  in the result, but the output must say what was missing rather than inventing
  an effectiveness measure.

The existing architecture overview explicitly keeps FAIR-CAM outside the Open
FAIR quantitative profile. GC-I017 should therefore add a separate analysis kind
and result contract, not mutate `FAIR_V3_0` arithmetic or the
`FairQuantitativeAnalysisResponse` shape.

## Incumbents To Reuse

- REST analysis surface: `GrcAnalysisController`, `GrcAnalysisService`, and the
  response-record mapping pattern under `api/grcanalysis`.
- Control substrate: `Control`, `ScopedControlImplementation`, `ControlTest`,
  `ControlEffectivenessAssessment`, `ControlEffectivenessRating`, and their
  project-scoped repositories.
- Mapping substrate: `RiskControlMapping`, `MappingControlRole`,
  `MappingEvidenceRef`, `RiskControlMappingRepository`,
  `RiskControlMappingFeedService`, `RiskControlCoverageService`, and
  `MethodologyInfluenceValidator`.
- Methodology substrate: `MethodologyProfile`, `MethodologyFamily`,
  `RiskAssessmentResult`, `RiskAssessmentResultRepository`, and profile-scoped
  `inputSchema` / `outputSchema` payload conventions.
- Evidence/state substrate: `EvidenceArtifact`, `Observation`, mapping-owned
  observations/evidence refs, `ControlTest.testDate`,
  `ControlEffectivenessAssessment.assessedAt`, and
  `EvidenceFreshnessAnalysisService` when freshness state is surfaced.
- MCP adapter surface: consolidated `gc_analyze`, `ANALYZE_KINDS`,
  `mcp/ground-control/lib.js` helper functions, `request()`, `buildUrl`,
  `RequestError`, `reqArg`, and the existing adapter tests in
  `mcp/ground-control/gc-analyze.test.js`.
- Cross-cutting helpers: Bean Validation / `@Validated`, Jackson enum binding,
  `DomainValidationException`, `NotFoundException`, `GlobalExceptionHandler`,
  `ErrorResponse`, `ApiPathMatrix`, security filters, `ActorFilter`,
  `ActorHolder`, `RequestLoggingFilter`, MDC, ADR-034 enum mirrors, and
  `@WebMvcTest` controller slices.

## Cross-Cutting Layers

- Security and authorization: keep the REST endpoint under `/api/v1/analysis/grc`.
  Bearer traffic must pass `IpAllowlistFilter`, `BearerTokenAuthFilter`,
  `ApiPathMatrix`, and then `ActorFilter`; browser/session traffic uses the
  browser chain with the same path matrix. Do not add route-local auth,
  caller-supplied actors, public analysis routes, caller-supplied headers, or
  caller-supplied tokens.
- Request parsing and validation: controller query parameters should use the
  existing `project`, `asOf`, and UUID filter style. `@Validated`, Bean
  Validation, and Jackson enum binding own shape. Domain services own
  same-project checks, profile/family compatibility, as-of/freshness semantics,
  allowed FAIR-CAM domain/effect labels, and evidence sufficiency limitations.
- Methodology payload validation: `MethodologyInfluenceValidator` already owns
  profile-keyed influence payload checks for `RiskControlMapping`. Reuse or
  extend that service boundary if FAIR-CAM needs structured influence fields.
  Do not copy schema checks into controllers, MCP handlers, response mappers, or
  per-endpoint switch blocks.
- Error envelope: throw existing domain exceptions and let
  `GlobalExceptionHandler` / `ErrorResponse` serialize them. Errors may name
  stable field names, enum values, IDs, profile keys, and bounds. They must not
  echo raw evidence payloads, observation values, methodology value bags,
  headers, bearer tokens, stack traces, or full request bodies.
- Logging and observability: use SLF4J with low-cardinality event names and
  stable IDs only. Request and actor context come from `RequestLoggingFilter`,
  `ActorFilter`, and MDC. Do not log raw control-test narratives, assessment
  rationale, evidence summaries, methodology inputs/outputs, bearer tokens, or
  large result payloads.
- Persistence: prefer a read-only response over new storage. If a later
  requirement asks to persist FAIR-CAM analytics as a durable conclusion, it
  needs the normal audited aggregate pattern: project scope, Flyway migration,
  audit shadow table, indexes for primary reads, Envers actor provenance, and
  no repository writes from controllers.
- MCP validation and transport: add only a new `gc_analyze` kind such as
  `fair_cam_control_analytics`. Public MCP args stay snake_case; backend query
  params stay camelCase; method-specific value-bag keys must remain opaque. Use
  `request()` against a fixed relative REST path. Do not add a top-level MCP
  tool, caller-supplied methods, absolute URLs, headers, tokens, shell-outs,
  direct database reads, or MCP-local calculations.
- API/MCP/frontend mirrors: any new public enum, including FAIR-CAM control
  domain, effect dimension, measurement scale, unit label, or analysis kind
  mirror, must follow ADR-034 in the same PR: backend enum/contract authority,
  MCP constants/Zod, frontend types/constants where exposed, docs, and policy
  inventory or focused mirror tests.
- Tests and gates: controller changes need `@WebMvcTest` coverage because
  Sonar coverage does not come from Testcontainers integration tests. Domain
  analytics need unit tests around domain attribution, as-of evidence selection,
  limitations, and effect dimensions. MCP needs adapter tests pinning URL,
  query params, response pass-through, and opaque-key behavior. Run
  `make policy` before completion.

## Result Contract Guardrails

The response must be structured for agents and explicitly methodology
attributed. It should include:

- `analysisKind` with a FAIR-CAM-specific value.
- `project`, `asOf`, and any scope filters used.
- `derivationMethod` with a stable versioned label.
- `scale` and `units` per measurement, not one global generic score when fields
  have different units.
- Per-control identity for catalog controls or scoped implementations.
- FAIR-CAM control domain labels: Loss Event Control, Variance Management
  Control, and Decision Support Control.
- Structured measurements for capability, coverage, operational performance,
  and methodology-specific effect.
- Structured `inputs`, `outputs`, `evidence`, and `limitations` sections.
- Effect dimensions that stay explicit: loss event frequency, loss magnitude,
  control reliability, and decision alignment.

Do not return only prose. Do not normalize FAIR-CAM results into one
`effectiveness`, `risk_score`, or `residual_risk` field.

## Extensibility

The extension seam is a methodology-aware control analytics service behind
`GrcAnalysisService`, parameterized by:

- `project`;
- `asOf` and, if freshness is part of the view, `freshnessWindowDays`;
- optional `controlId` or `scopedImplementationId`;
- optional `riskScenarioId`, `riskRegisterRecordId`, `threatModelId`, or
  `riskAssessmentResultId`;
- optional `methodologyProfileId` / `profileKey`;
- optional FAIR-CAM domain and effect-dimension filters.

The next likely changes are another FAIR-CAM measurement, a stricter
methodology-influence schema, or a frontend portfolio/control workspace view.
Those should add a domain handler, schema/profile data, and response fields
without changing MCP transport, auth, graph adapters, error envelopes, or the
canonical mapping/evidence aggregates.

Local typed records inside the service are acceptable for parsing and result
assembly. Do not promote a repo-wide measurement framework until at least one
other methodology reuses it.

## Gotchas And Anti-Patterns

- Do not treat `ControlStatus.OPERATIONAL`, generic `Control.effectiveness`, or
  link presence as FAIR-CAM evidence.
- Do not collapse Loss Event Control, Variance Management Control, and Decision
  Support Control into `ControlFunction` or `MappingControlRole` without
  preserving FAIR-CAM attribution and source evidence.
- Do not store FAIR-CAM factor effects on `Control`, `RiskScenario`,
  `RiskRegisterRecord`, `ThreatModel`, or `RiskAssessmentResult` as global
  truth. Effects are contextual to mapping, scope, methodology, evidence, and
  time.
- Do not mutate the Open FAIR quantitative `fair_quantitative` endpoint or the
  `FAIR_V3_0` LEF/LM/ALE arithmetic to satisfy control analytics.
- Do not add a `FairCamControl` aggregate, a duplicate control-effectiveness
  table, a duplicate JSON schema validator, a duplicate exception hierarchy, a
  duplicate audit writer, a route-local security filter, or an MCP-only engine.
- Do not recursively camel/snake-case methodology-defined value-bag keys.
- Do not hide missing tests, missing assessments, stale evidence, mixed control
  scopes, or unsupported effect calculations. Emit `limitations`.
- Do not add graph contributors or AGE properties unless a concrete traversal
  requirement needs them; if added, follow ADR-032 allowlists and projection
  tests.

## Non-Goals

- No implementation of GC-I017 in this preflight.
- No new persistence aggregate, migration, graph subsystem, external
  integration, config namespace, secret, subprocess, or workflow side-effect
  channel for this requirement.
- No change to Open FAIR quantitative risk analysis, NIST SP 800-30 analysis,
  compliance monitoring, continuous GRC screening, or risk-control mapping CRUD
  semantics beyond preserving their boundaries.
- No claim that a generic effectiveness label satisfies FAIR-CAM control
  physiology.
