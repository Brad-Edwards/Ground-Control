# NIST SP 800-30 Risk Assessment Preflight

Issue: #721
Requirement: GC-T014

This is architecture guardrail guidance for NIST SP 800-30 Rev. 1-style
risk assessment support. It is not an implementation plan.

## Boundary

GC-T014 is methodology-specific risk-assessment execution and exposure. The
durable assessment record remains `RiskAssessmentResult`, bound to a
same-project `RiskScenario` and `MethodologyProfile`; the public analysis
surface belongs under `/api/v1/analysis/grc/*` and the consolidated MCP
`gc_analyze` kind set. Do not introduce a parallel NIST assessment aggregate,
top-level MCP tool, or generic risk-score subsystem.

Keep the NIST concepts distinct:

- `RiskScenario` owns the scoped scenario statement (`threat`, `method`,
  `asset`, `effect`, `timeHorizon`). It is not the NIST assessment result and
  must not grow likelihood, impact, vulnerability, or predisposing-condition
  score fields.
- `ThreatModel` owns threat-source/threat-event/effect modeling for threat
  analysis. A NIST assessment may reference or copy those facts as inputs, but
  it must not mutate threat models or treat them as assessment results.
- `Finding` owns governed vulnerability records; `Observation` owns
  time-bounded asset facts such as exposure, patch state, and configuration.
  NIST inputs may cite these through existing links/observation ids and
  evidence refs; do not create NIST-only vulnerability/evidence tables.
- `MethodologyProfile` owns reusable NIST profile identity, version, scales,
  input/output schema vocabulary, and matrix semantics. It does not store
  individual assessment values.
- `RiskAssessmentResult` owns the actual NIST inputs, computed/assessed
  outputs, evidence refs, observations, confidence, analyst identity, timing,
  and approval state.

NIST support must model, at contract level, threat sources, threat events,
vulnerabilities, predisposing conditions, threat-event relevance, likelihood
of initiation or occurrence, likelihood of adverse impact, overall likelihood,
impact level, assessment timeframe, and whether the threat event is adversarial
or non-adversarial. Storing only `likelihood` and `impact`, or a generic
`risk_score`, does not satisfy the requirement.

## Incumbents To Reuse

- Persistence substrate: `RiskAssessmentResult`, `RiskAssessmentResultService`,
  command records, `RiskAssessmentResultRepository`, `MethodologyProfile`,
  `MethodologyProfileService`, and project-scoped repository methods.
- Existing NIST profile seed: `NIST_SP800_30_R1` in the Flyway seed/update
  migrations and `MethodologyProfileService` default seeding. If the NIST
  schema changes, keep database seed data and runtime first-read seeding
  aligned.
- REST analysis surface: `GrcAnalysisController`, `GrcAnalysisService`, and
  response-record mapping under `api/grcanalysis`. Controllers resolve project
  once, validate request shape, and delegate; services own semantic validation
  and methodology execution.
- REST assessment CRUD: `RiskAssessmentResultController` remains the durable
  create/update/read surface for assessment rows. The GRC analysis endpoint
  should expose or derive NIST methodology results without bypassing that
  aggregate boundary.
- MCP surface: extend `gc_analyze` with one new `kind` and a helper in
  `mcp/ground-control/lib.js` that calls the fixed REST endpoint through
  `request()`. Keep `gc_risk_governance` for CRUD on `risk_assessment_result`.
- JSON value bags: use `JacksonTextCollectionConverters` for stored
  `inputFactors`, `computedOutputs`, and `uncertaintyMetadata`; do not add
  feature-local JSON string parsing or another `ObjectMapper` convention.
- Evidence and context: use `observationIds`, `evidenceRefs`,
  `RiskScenarioLink`, `ThreatModelLink`, `FindingLink`, and graph target
  resolution for same-project context. `RiskGraphProjectionContributor`,
  `GraphIds`, `GraphEntityType`, and `GraphTargetResolverService` remain the
  graph path.
- Cross-cutting helpers: Bean Validation / `@Validated`, domain exceptions,
  `GlobalExceptionHandler` / `ErrorResponse`, `ApiPathMatrix`, security
  filters, `ActorFilter` / `ActorHolder`, `RequestLoggingFilter`, MDC, Envers,
  Flyway, `@WebMvcTest`, MCP adapter tests, and `make policy`.

## Cross-Cutting Layers

- Security and authorization: new REST routes stay under `/api/v1/**`. Bearer
  requests pass `IpAllowlistFilter`, `BearerTokenAuthFilter`, Spring
  authorization via `ApiPathMatrix`, then `ActorFilter`; browser/session
  requests pass the browser chain with the same path matrix. Do not add
  route-local authorization, caller-supplied actors, headers, tokens, or public
  NIST endpoints.
- Request parsing and validation: controller DTO/query annotations own shape
  checks such as UUIDs, positive windows, and enum parsing. Domain services own
  same-project checks, NIST-family/profile compatibility, adversarial versus
  non-adversarial required-field rules, ordinal-band validity, and whether an
  overall likelihood is computed or analyst-supplied.
- Methodology schema validation: profile schemas exist, but there is no
  canonical JSON Schema validator. If GC-T014 validates profile-defined input
  or output schemas, introduce one reusable validation component behind the
  service boundary with one structured `DomainValidationException` detail
  shape. Do not copy schema checks into controllers, MCP handlers, migrations,
  or per-field switch blocks.
- Error envelope: throw `NotFoundException`, `ConflictException`, and
  `DomainValidationException`; `GlobalExceptionHandler` and `ErrorResponse` are
  the only HTTP error shape. Detail may include stable field paths and valid
  enum/band labels, but must not echo raw assessment inputs, evidence payloads,
  observation values, headers, stack traces, or tokens.
- Logging and audit: Envers plus `ActorFilter`, `ActorHolder`, and
  `GroundControlRevisionListener` provide audit provenance. SLF4J logs should
  use stable ids, profile keys, and low-cardinality status/band labels only.
  Never log full NIST inputs, vulnerability narratives, predisposing-condition
  text, evidence bodies, bearer tokens, or session identifiers.
- MCP validation and transport: public MCP args stay snake_case and Zod
  checked. Use `request()`, `buildUrl`, `addAuthorizationHeader`,
  `RequestError`, `pick`, and the shared snake/camel mapping. Do not add
  per-tool fetch clients, absolute URLs, caller-supplied auth, shell-outs,
  direct database reads, or MCP-local methodology execution.
- Opaque methodology values: NIST schema keys inside `inputFactors`,
  `computedOutputs`, and `uncertaintyMetadata` must remain stable. If MCP sends
  these maps, add them to the shared opaque-value-key guard so recursive
  camel/snake conversion does not rewrite profile-defined keys such as
  `threat_event_relevance`, legacy `threat_source_relevance`, or
  `likelihood_adverse_impact`.
- Config and OS/runtime exposure: GC-T014 should not require new secrets,
  subprocesses, CLI arguments, network calls, or token-in-argv handling. Any
  future configurable matrix or validation dependency must use validated
  `@ConfigurationProperties` and keep secrets out of argv, logs, and errors.
- Persistence and migrations: new columns or audited entities require Flyway,
  audit-table parity, indexes for primary reads, migration smoke updates, and
  audit-retention review. Profile-data-only changes still need seed parity and
  tests proving the NIST profile exposes the required vocabulary.
- Contract mirrors and policy: API-visible enums such as threat-event kind,
  threat-event relevance, likelihood band, or impact band must follow ADR-034:
  backend enum as authority, MCP constants/Zod, frontend types/constants where
  mirrored, docs, and policy inventory or focused mirror tests together.

## NIST Result Contract Guardrails

The analysis response must be structured for agent use and methodology
attributed. It should carry `analysisKind`, project, as-of or assessment
timestamp, `methodologyProfileId` / `profileKey` / `family` / `version`,
`derivationMethod`, `scale`, `units`, structured `inputs`, structured
`outputs`, `evidence`, and `limitations`.

For NIST, `inputs` must preserve at least:

- threat source, threat event, and `threatEventKind` (`ADVERSARIAL` or
  `NON_ADVERSARIAL`);
- vulnerabilities and predisposing conditions as distinct collections or
  clearly typed objects, not one prose blob;
- threat-event relevance, with adversarial source attributes such as
  capability/intent/targeting preserved as separate threat-source context;
- likelihood of initiation or occurrence;
- likelihood that the threat event results in adverse impact;
- assessment timeframe and evidence references.

`outputs` must preserve overall likelihood and impact level as ordinal NIST
bands with explicit scale and units. A matrix cell or ordinal rank may be
included only with method and conversion-rule labels. Do not normalize NIST
bands into a cross-method numeric score or compare them to FAIR monetary
outputs without an explicit method label and conversion rule.

`limitations` must be explicit when predisposing-condition coverage is
incomplete, source relevance is not established, evidence is stale or missing,
non-adversarial likelihood lacks frequency data, or schema validation was not
performed.

## Extensibility

The extension seam is methodology profile plus a small backend NIST execution
service keyed by `methodologyProfileId` or profile key/version. The next
reasonable variation is another NIST profile version, an organization-specific
ordinal scale, or a second methodology analysis. Those should require profile
data and one service strategy/handler, not new MCP transport, auth rules,
error envelopes, graph clients, or persistence aggregates.

Parameterize the seam by project, `riskAssessmentResultId` or scenario/profile
selection, optional `asOf`, methodology profile identity, and threat-event kind.
Adversarial and non-adversarial branches should share the result contract but
have separate required-field rules; do not force non-adversarial events through
adversarial-only fields such as intent.

## Gotchas And Anti-Patterns

- Do not treat the FAIR-CRST `RiskScenario.method` field as the NIST threat
  event, or `RiskScenario.threat` as the whole NIST threat source analysis.
- Do not store NIST vulnerabilities or predisposing conditions in
  `RiskRegisterRecord.decisionMetadata`, `RiskScenario` fields, treatment-plan
  metadata, or threat-model narratives because the first DTO is convenient.
- Do not make `ThreatModel` the assessment engine or persist NIST likelihood,
  impact, or approval state on a threat model.
- Do not add a new top-level MCP tool, generic `risk_score`, Cypher/SQL
  passthrough, direct AGE writes, duplicate error envelope, duplicate auth
  filter, duplicate JSON parser, or duplicate exception hierarchy.
- Do not recursively rename methodology-defined map keys across MCP transport.
- Do not silently accept a non-NIST `MethodologyProfile` for the NIST analysis
  endpoint.
- Do not mark GC-T014 satisfied by the existing seeded NIST profile alone; the
  current seed is a vocabulary start, not a workflow engine or full NIST
  decomposition.

## Non-Goals

- No implementation of GC-T014 in this preflight.
- No FAIR, FAIR-CAM, ISO 27005, compliance posture, vendor-risk, or evidence
  freshness engine changes beyond preserving their boundaries.
- No new risk register, treatment plan, vulnerability, evidence, vendor,
  questionnaire, or threat-model aggregate.
- No OpenAPI/codegen migration, generic workflow engine, external NIST content
  downloader, new security scheme, or runtime secret/config surface.
