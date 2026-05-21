# Threat Scenario-Requirement Linking Preflight

Requirement: GC-H002
Issue: #734

This is architecture guardrail guidance for linking threat-model entries and
risk scenarios to affected assets or boundaries and to mitigating requirements,
controls, and artifacts. It is not an implementation plan.

## Boundary

GC-H002 is a traceability requirement over existing concepts, not a new
all-purpose relationship aggregate.

- `ThreatModel` remains the upstream threat-model entry: threat source, threat
  event, affected context, and effect. It must not inherit risk-register,
  treatment, likelihood, impact, or approval semantics.
- `RiskScenario` remains the scoped risk scenario: threat source, threat event,
  affected object, vulnerability, consequence, and time horizon. It must not
  become the authoritative threat-model record.
- `OperationalAsset`, including `AssetType.BOUNDARY`, remains the structured
  operational scope. Free-text affected-object fields are context, not the graph
  authority when a project-scoped asset or boundary exists.
- `Requirement`, `Control`, evidence, audit, findings, and external artifacts
  remain their own target concepts. Links connect them; they should not be
  copied into threat or risk records as embedded mini-schemas.

The canonical write side should reuse the existing dual-mode link surfaces:
`ThreatModelLink` for threat-model-owned edges and `RiskScenarioLink` for
risk-scenario-owned edges. `AssetLink` may support asset-owned traversal, but it
should not become a second mandatory write for every threat/risk link unless a
service-owned invariant keeps the projections consistent. `TraceabilityLink` is
requirement-centric and artifact-oriented; do not use it as the new canonical
internal target store for project-scoped threat/risk links.

## Incumbents To Reuse

- ADR-024: threat modeling stays distinct from risk management and owns
  `ThreatModelLink`.
- ADR-019 and ADR-020: affected assets and boundaries are `OperationalAsset`
  records, with `AssetLink` available for asset-anchored cross-entity traversal.
- Risk-domain services and links: `RiskScenario`, `RiskScenarioLink`,
  `RiskScenarioLinkService`, and `RiskScenarioLinkRepository`.
- Threat-domain services and links: `ThreatModel`, `ThreatModelLink`,
  `ThreatModelLinkService`, and `ThreatModelLinkRepository`.
- Project-scoped target validation: `GraphTargetResolverService`, using
  `targetEntityId` for first-class internal targets and `targetIdentifier` only
  for external or not-yet-modeled artifacts.
- Mixed graph projection: `GraphEntityType`, `GraphIds`,
  `GraphProjectionContributor`, `ThreatModelGraphProjectionContributor`,
  `RiskGraphProjectionContributor`, `AssetGraphProjectionContributor`, and
  `AgeGraphService`. JPA remains the source of truth.
- Requirement artifact traceability: existing `TraceabilityLink` and
  `TraceabilityService` are still valid for external artifacts and legacy
  requirement-centric coverage, but not as a replacement for internal
  project-scoped threat/risk links.
- MCP link adapter shape: `link-create.js`, action-scoped body allowlists,
  `pick`, `reqArg`, `toCamelCase`, `RequestError`, and backend error
  propagation in `mcp/ground-control/lib.js`.

## Cross-Cutting Layers

- Security: any REST additions stay under `/api/v1/**` and the shared
  `ApiSecurityConfig` / `BrowserSecurityConfig` path matrix. Bearer callers pass
  `IpAllowlistFilter`, `BearerTokenAuthFilter`, Spring authorization, and
  `ActorFilter`; browser callers use the ADR-037 browser chain with the same
  API path matrix. Do not add route-local auth, actor request fields, or
  feature-local token stores.
- Request parsing and validation: Jackson enum binding and Bean Validation own
  DTO shape. Services own same-project validation, internal-vs-external target
  semantics, duplicate detection, lifecycle rules, and delete/reference checks.
  Do not duplicate `GraphTargetResolverService` in controllers, MCP, or frontend
  code.
- Error envelope: throw existing `NotFoundException`, `ConflictException`, and
  `DomainValidationException`; let `GlobalExceptionHandler` and `ErrorResponse`
  serialize. Errors may name stable target type/id fields, but must not echo raw
  threat narratives, exploit text, evidence payloads, Authorization headers,
  bearer tokens, or stack traces.
- Audit and observability: audited link/entity changes use Envers and service
  deletion paths so audit rows are written. Actor provenance comes from
  `ActorFilter`, `ActorHolder`, and `GroundControlRevisionListener`. Logs use
  SLF4J with stable ids and low-cardinality enums, not raw request bodies.
- Persistence: Flyway is the schema source of truth. Any new persisted enum
  values, columns, or tables require audit-table parity, project-scope indexes,
  reverse lookup indexes, and service-level delete handling that does not rely
  on database cascade to write audit history.
- Config and OS/runtime exposure: GC-H002 should require no new secrets,
  environment bindings, subprocesses, network clients, or CLI argv token
  handling. If a future importer adds them, use `@ConfigurationProperties` with
  startup validation and keep secrets out of argv, logs, persisted narratives,
  and error envelopes.
- Contract mirrors: API-visible enum or DTO changes must update backend tests,
  MCP constants/tests, frontend types/graph constants where mirrored, and the
  ADR-034 inventory if the enum is covered there.

## Extensibility

The extensibility seam is the target inventory plus a graph/read-model query
parameterized by project, source kind (`THREAT_MODEL` or `RISK_SCENARIO`), source
id or uid, target type filters, and traversal depth where needed. Adding future
targets such as audits, findings, scanner records, architecture artifacts, or
control effectiveness results should extend the relevant target enum,
`GraphTargetResolverService`, graph projection contributor, MCP/frontend mirrors,
and tests; it should not require a new graph substrate or a second resolver
family.

Derived traceability such as "threat source/event -> asset/boundary ->
requirement/control/evidence" belongs on the read side over the same persisted
links and mixed graph. The read side may union threat-owned, risk-owned, and
asset-owned edges, but writes need one canonical owner or an explicit service
invariant that prevents divergent duplicate facts.

## Gotchas And Anti-Patterns

- Do not create a generic `ThreatScenarioRequirementLink` table when the
  existing threat/risk link surfaces already model source-owned internal and
  external targets.
- Do not link first-class requirements, assets, controls, risk scenarios,
  threat models, observations, findings, audits, evidence, or assessments by
  raw UID/identifier when a project-scoped UUID target exists.
- Do not treat `RiskScenarioService.findLinkedRequirements(...)`'s existing
  `TraceabilityLink(ArtifactType.RISK_SCENARIO, scenario.uid)` lookup as the new
  canonical internal-link contract. That path is requirement-centric,
  identifier-based, and easy to mis-scope across projects if reused without a
  project-aware query.
- Do not require callers to create both a `ThreatModelLink`/`RiskScenarioLink`
  and an `AssetLink` for the same semantic fact unless the service owns
  mechanical consistency.
- Do not store asset or boundary context only in `RiskScenario.affectedObject`,
  `ThreatModel.narrative`, `Control.implementationScope`, or arbitrary metadata
  when an `OperationalAsset` exists.
- Do not write graph rows directly from controllers/services or add a
  feature-specific AGE materialization path.
- Do not add duplicate exception hierarchies, validators, auth filters, audit
  writers, JSON serializers, workflow engines, or frontend-only schemas.

## Non-Goals

- No implementation of GC-H002 in this preflight.
- No replacement of `ThreatModel`, `RiskScenario`, `OperationalAsset`,
  `Requirement`, `Control`, `TraceabilityLink`, `AssetLink`,
  `ThreatModelLink`, or `RiskScenarioLink` with a generic graph table.
- No automatic risk scoring, residual-risk mutation, treatment-plan creation,
  requirement status transition, control effectiveness evaluation, or threat
  lifecycle transition from link creation.
- No scanner, ticketing, GitHub, architecture-model import, or external threat
  intelligence integration.
- No new security scheme, config surface, audit writer, error envelope, graph
  writer, or MCP transport helper.
