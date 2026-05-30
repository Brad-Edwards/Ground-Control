# Risk Scenario Workspace Preflight

Requirement: GC-Q009
Issue: #747

This is architecture guardrail guidance for the Risk Scenario workspace. It is
not an implementation plan.

## Boundary

GC-Q009 is a human workspace over the existing graph-native risk model, not a
new canonical risk store.

- `RiskScenario` remains the scoped loss-scenario statement. It must not absorb
  assessment values, register governance, treatment execution, control
  effectiveness, finding lifecycle, evidence derivation, or asset topology.
- `RiskAssessmentResult` remains the methodology-scoped assessment conclusion.
  Compare/review views may show assessment history, but they must not rewrite
  methodology inputs or computed outputs from workspace display code.
- `RiskRegisterRecord` remains the governance and decision record for one
  scenario or a deliberate scenario group.
- `TreatmentPlan` remains the treatment strategy and action aggregate.
- `RiskControlMapping` remains the canonical owner for control-to-risk
  relationships with role, objective, scope, methodology influence,
  observations, and evidence provenance.
- `OperationalAsset`, `AssetRelation`, `Observation`, `Finding`,
  `EvidenceArtifact`, `Audit`, `Control`, and their link substrates keep their
  existing ownership semantics. A workspace may union them for display, but it
  must not require duplicate writes for one semantic fact unless a service-owned
  invariant keeps those writes consistent.

If the workspace needs a composed read endpoint, keep it as a read-only
application/domain result such as the GC-Q010 threat-model workspace pattern:
no JPA workspace aggregate, no migration, no AGE write path, and no durable
"workspace row" that becomes a second source of truth.

## Incumbents To Reuse

- Risk statement and links: `RiskScenario`, `RiskScenarioService`,
  `RiskScenarioLinkService`, `RiskScenarioRepository`,
  `RiskScenarioLinkRepository`, and the risk-scenario request/response records.
- Assessment, register, and treatment: `RiskAssessmentResult`,
  `RiskRegisterRecord`, `TreatmentPlan`, their services, repositories,
  controllers, state enums, and status or approval transition rules.
- Control-to-risk context: `RiskControlMapping`,
  `ScopedControlImplementation`, `RiskControlMappingService`,
  `RiskControlMappingFeedService`, `RiskControlCoverageService`, and ADR-052.
- Assets and observations: `OperationalAsset`, `AssetRelation`,
  `Observation`, `AssetService`, `AssetTopologyService`,
  `ObservationService`, and project-scoped repository queries.
- Findings, evidence, and audits: `Finding`, `FindingLink`,
  `EvidenceArtifact`, `EvidenceFreshnessAnalysisService`, `Audit`,
  `AuditLink`, and their existing service boundaries.
- Project-scoped target validation: `GraphTargetResolverService` for
  internal/external target shape and same-project existence checks.
- Mixed graph read model: `GraphEntityType`, `GraphIds`, `GraphProjection`,
  `GraphProjectionContributor`, `GraphProjectionRegistryService`,
  `MixedGraphService`, `MixedGraphClient`, and `GraphTraversalLimits`.
- API and frontend transport: thin controllers, request/response records,
  `@Valid` / `@Validated`, `ProjectService.resolveProjectId` /
  `requireProjectId`, `frontend/src/lib/api-client.ts`, project-scoped TanStack
  Query hooks, and `frontend/src/types/api.ts` mirrors.
- MCP read/write boundaries where exposed: consolidated named tools for writes,
  `gc_query` for allowlisted GETs, and the shared adapter helpers in
  `mcp/ground-control/lib.js`.

## Cross-Cutting Layers

- Security: workspace routes stay under `/api/v1/**` and pass through the
  shared ADR-026 and ADR-037 path matrix. Bearer traffic uses the IP allowlist,
  bearer-token filter, authorization filter, and `ActorFilter`; browser traffic
  uses the session/CSRF chain and the same authorization model. Normal
  workspace routes must not live under `/api/v1/admin/**` unless they perform an
  admin-only side effect.
- Browser writes: React mutations must use `apiFetch` / `apiDelete` so CSRF
  headers and same-origin credentials are attached consistently. Do not put
  bearer tokens in browser storage to make the workspace work.
- Project isolation: resolve exactly one project at the API boundary, then use
  project-scoped repository methods, service checks, `GraphTargetResolverService`,
  and graph node IDs validated against that project's projection. Never compare
  UIDs, link identifiers, graph node IDs, or assessment ids across projects
  without the project id.
- Validation: controllers own shape validation and query-param validation;
  services own semantic rules, same-project checks, duplicate detection,
  lifecycle transitions, control-to-risk consistency, and internal-vs-external
  link semantics. Graph traversal and comparison inputs must remain bounded by
  `GraphTraversalLimits` or an equally explicit workspace-specific cap.
- Error envelopes: throw existing `NotFoundException`, `ConflictException`, and
  `DomainValidationException`. Let `GlobalExceptionHandler` serialize
  `ErrorResponse`; do not add workspace-local error wrappers.
- Error leakage: validation details may name fields, entity ids, enum values, or
  stable codes, but must not echo raw scenario prose, evidence summaries,
  observation payloads, methodology payloads, bearer tokens, CSRF tokens,
  Authorization headers, SQL, Cypher, stack traces, or request bodies.
- Graph and AGE safety: controllers and domain services must not assemble SQL,
  Cypher, AGE labels, relationship names, or graph property maps directly. Use
  the mixed-graph contracts so `AgeGraphService` keeps parameter binding,
  property allowlisting, graph-name validation, projection caps, and AGE-disabled
  fallback centralized.
- Audit and observability: mutations continue through owning aggregate services
  and Envers-audited entities. Actor provenance comes from `ActorFilter`,
  `ActorHolder`, MDC, and `GroundControlRevisionListener`; clients must not
  supply audit actor identity in workspace request bodies. Logs should use SLF4J
  with stable ids, counts, and low-cardinality enum-like values, not raw
  narratives or evidence bodies.
- Configuration and OS exposure: GC-Q009 should require no new env vars,
  secrets, subprocesses, shell-outs, network clients, or argv-carried tokens. If
  future import/sync behavior adds configuration, use `@ConfigurationProperties`
  with startup validation and keep secrets out of argv, logs, persisted
  narrative fields, and error envelopes.
- Contract mirrors: API-visible enum or DTO changes must update backend tests,
  frontend `api.ts` mirrors, MCP schemas/constants when exposed to tools, graph
  constants, API docs, and ADR-034 policy inventory where covered.

## Extensibility

The extension seam is a project-scoped workspace query parameterized by scope
and evaluation context: project, scenario ids or graph node ids, asset or
boundary filters, risk status, methodology profile, assessment approval state,
treatment status, finding status or severity, evidence freshness, `asOf`, and a
small bounded comparison set.

Comparison should start as read-time composition over canonical records. If a
future requirement needs durable comparison snapshots, that should be its own
aggregate decision with explicit inputs, generated-at time, actor provenance,
and invalidation semantics. Do not let cached or persisted workspace snapshots
become the workflow record for risk decisions.

Future workspace additions such as vendor records, questionnaires, compliance
framework mappings, attack paths, or remediation-plan aggregates should extend
the relevant target enum, `GraphTargetResolverService`, graph projection
contributor, API DTO mirror, frontend type mirror, MCP mirror if exposed, and
tests. They should not require a second graph substrate, a universal relation
table, or workspace-owned copies of source aggregate fields.

If caching becomes necessary, key it by project plus every query parameter that
affects the workspace shape and define invalidation against owning aggregate
writes. The cache is a derived optimization only.

## Gotchas And Anti-Patterns

- Do not add a `RiskScenarioWorkspace` JPA aggregate merely to join records that
  are already owned by risk, asset, control, finding, audit, evidence, and graph
  services.
- Do not conflate risk scenarios with threat models, assessment results,
  register records, treatment plans, findings, evidence artifacts, observations,
  or controls.
- Do not use `RiskScenarioLink` plus `ControlLink` as a substitute for
  `RiskControlMapping` when control role, objective, methodology influence,
  observations, or evidence provenance matter.
- Do not store linked assets, controls, findings, evidence, audits, treatments,
  or assessments only as free-text lists when first-class project-scoped records
  exist.
- Do not infer scenario-review staleness from `updatedAt`, Envers revisions,
  lifecycle status, GitHub issue timestamps, or graph materialization time. Use
  explicit review fields or existing evidence/assessment freshness semantics.
- Do not add workspace-local exception hierarchies, auth guards, JSON parsers,
  graph writers, audit writers, logging channels, request clients, or MCP
  transport helpers.
- Do not broaden MCP `gc_query` for writes, absolute URLs, caller-supplied
  headers, nested params, query strings embedded in paths, or Cypher passthrough.
- Do not project large free text, arbitrary metadata, evidence bodies, scanner
  payloads, or secrets into graph-wide node properties or logs.
- Do not rely on Testcontainers-only integration tests for Sonar coverage of the
  controller contract; use focused `@WebMvcTest` slices where a new controller
  is added.

## Non-Goals

- No implementation of GC-Q009 in this preflight.
- No new domain aggregate, migration, repository, controller, REST endpoint,
  MCP tool, AGE query, or frontend screen is specified here.
- No replacement of `RiskScenario`, `RiskAssessmentResult`,
  `RiskRegisterRecord`, `TreatmentPlan`, `RiskControlMapping`,
  `OperationalAsset`, `AssetRelation`, `Observation`, `Finding`,
  `EvidenceArtifact`, `Audit`, `Control`, or the mixed graph contracts.
- No automated risk scoring, FAIR calculation engine, Monte Carlo simulation,
  treatment generation, requirement status transition, or control effectiveness
  evaluation from simply viewing or comparing the workspace.
- No new security scheme, credential store, config namespace, audit writer,
  error envelope, graph writer, external integration, or privileged GitHub
  side-effect path.
