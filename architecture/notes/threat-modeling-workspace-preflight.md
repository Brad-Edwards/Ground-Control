# Threat Modeling Workspace Preflight

Requirement: GC-Q010
Issue: #748

This is architecture guardrail guidance for the threat-modeling workspace. It
is not an implementation plan.

## Boundary

GC-Q010 is a read-side workspace over existing graph-native security concepts,
not a new canonical data store.

- `ThreatModel` remains the upstream threat-model entry. It must not absorb
  risk-register, assessment, treatment, approval, or residual-risk semantics.
- `OperationalAsset` remains the authoritative scoped asset and boundary model.
  Trust/system boundaries are assets, including `AssetType.BOUNDARY`; do not
  create a workspace-only boundary table or store boundary scope only in
  threat-model narrative text.
- `AssetRelation` remains the authoritative flow/topology edge. Data flows,
  trust-boundary crossings, communication, and dependency lines should be read
  from asset relations and the mixed graph projection, not copied into
  threat-model records.
- `ThreatModelLink`, `ControlLink`, `RiskControlMapping`, and
  `TraceabilityLink` keep their existing ownership semantics. A workspace may
  union them for display, but it must not require duplicate writes for one
  semantic fact unless a service-owned invariant keeps those writes consistent.
- Stale indicators are derived view state. Evidence, observation, and
  control-test freshness should reuse the existing GRC freshness analysis. If
  the product needs threat-model-review staleness itself, model explicit review
  temporal fields or a derived review read model; do not infer it from
  `updatedAt`, Envers revisions, GitHub issue timestamps, or lifecycle status.

## Incumbents To Reuse

- Threat-model boundary: ADR-024, `ThreatModel`, `ThreatModelLink`,
  `ThreatModelService`, and `ThreatModelLinkService`.
- Asset and boundary topology: ADR-019, ADR-020, `OperationalAsset`,
  `AssetRelation`, `AssetService`, `AssetTopologyService`, and
  `OperationalAssetRepository.findByProjectIdAndArchivedAtIsNullAndFilters`.
- Project-scoped internal target validation: `GraphTargetResolverService`;
  use `targetEntityId` for first-class internal targets and `targetIdentifier`
  only for external or not-yet-modeled artifacts.
- Mixed graph projection: `GraphEntityType`, `GraphIds`, `GraphProjection`,
  `GraphProjectionContributor`, `GraphProjectionRegistryService`,
  `MixedGraphService`, `MixedGraphClient`, `GraphTraversalLimits`, and
  `AgeGraphService`.
- Security traceability read model: `SecurityTrace` and
  `SecurityTraceResponse` for threat/risk source -> assets -> controls ->
  requirements -> requirement artifacts.
- Freshness/staleness: `EvidenceFreshnessAnalysisService`,
  `EvidenceFreshnessResult`, and `/api/v1/analysis/grc/evidence-freshness`.
- API and frontend transport: request/response records with Bean Validation,
  `GlobalExceptionHandler` / `ErrorResponse`, `frontend/src/lib/api-client.ts`,
  project-scoped TanStack Query hooks, and the API type mirrors in
  `frontend/src/types/api.ts`.

## Cross-Cutting Layers

- Security: workspace reads stay under `/api/v1/**` and pass through the shared
  ADR-026 / ADR-037 auth surface. Bearer traffic passes the IP allowlist,
  bearer-token filter, shared path matrix, and `ActorFilter`; browser traffic
  uses the session/CSRF chain and `apiFetch`. A normal workspace route should
  not be placed under `/api/v1/admin/**` unless it performs an admin-only side
  effect.
- Project isolation: resolve exactly one project at the API boundary, then use
  project-scoped repositories, `GraphTargetResolverService`, and graph node IDs
  validated against that project's projection. Never compare UIDs, link
  identifiers, or graph node IDs across projects without the project id.
- Validation: controllers own request shape with Bean Validation and enum
  parsing; services own semantic validation, same-project checks, duplicate
  detection, and stale/freshness parameter validation. Graph traversal inputs
  must remain bounded by `GraphTraversalLimits`.
- Error envelopes: throw existing `NotFoundException`, `ConflictException`,
  and `DomainValidationException`. Let `GlobalExceptionHandler` serialize the
  standard `ErrorResponse`; do not add a workspace-local error envelope.
- AGE and graph cost safety: controllers and domain services must not assemble
  SQL, Cypher, labels, relationship names, or property maps directly. Use the
  mixed graph contracts so `AgeGraphService` keeps parameter binding, allowlist,
  fallback, and projection-cap behavior centralized.
- Audit and observability: read-side workspace queries are not audit events by
  themselves. Mutations to underlying aggregates continue through their owning
  services and Envers-audited entities. Logs should use SLF4J with stable ids,
  counts, and low-cardinality enum-like values; do not log raw threat
  narratives, evidence bodies, request bodies, tokens, or Authorization headers.
- Config and OS exposure: this workspace should need no new secrets,
  subprocesses, shell calls, network clients, or argv-token handling. If a
  future import/sync path adds configuration, use `@ConfigurationProperties`
  and keep secrets out of logs, errors, persisted narrative fields, and process
  argv.
- Contract mirrors: public enum or DTO changes must update backend request and
  response tests, MCP Zod/constants when exposed through tools, frontend
  `api.ts` mirrors, graph constants, and ADR-034 policy inventory where covered.

## Extensibility

The extension seam is a project-scoped, read-only workspace query parameterized
by scope and evaluation time: project, asset/boundary filters, entity-type
filters, source id/uid filters, `asOf`, and freshness/review windows where
staleness is shown.

Future workspace additions such as attack paths, findings, audits, evidence
artifacts, architecture artifacts, or control-effectiveness results should add
or extend the relevant target enum, `GraphTargetResolverService`,
`GraphProjectionContributor`, API DTO mirror, frontend type mirror, and tests.
They should not require a second graph substrate, a generic relationship table,
or workspace-owned copies of source aggregate fields.

If caching becomes necessary, key it by project plus every query parameter that
affects the workspace shape, define invalidation against the owning aggregate
writes, and keep the cache a derived optimization. Do not let a cached
workspace snapshot become the durable workflow record.

## Gotchas And Anti-Patterns

- Do not conflate threat models with risk scenarios, risk assessment results,
  risk register records, treatment plans, or control effectiveness results.
- Do not add a `ThreatModelWorkspace` JPA aggregate simply to join data that is
  already owned by assets, threat links, controls, requirements, risk-control
  mappings, observations, evidence, and graph projections.
- Do not use free-text UIDs or target identifiers for first-class internal
  targets when a project-scoped UUID target exists.
- Do not write graph rows directly from controllers or services, and do not add
  a threat-workspace-specific AGE materialization path.
- Do not infer stale review state from `updatedAt`, Envers revisions, lifecycle
  status, or issue timestamps. Those fields answer different questions.
- Do not duplicate exception hierarchies, auth filters, JSON serializers,
  target resolvers, graph traversal limits, or frontend request clients.
- Do not add large evidence bodies, secrets, scanner payloads, or raw exploit
  narratives to graph-wide node properties, logs, or validation errors.

## Non-Goals

- No implementation of GC-Q010 in this note.
- No new domain aggregate, migration, repository, REST endpoint, MCP tool, AGE
  query, or frontend screen is specified here.
- No replacement of `ThreatModel`, `OperationalAsset`, `AssetRelation`,
  `ThreatModelLink`, `ControlLink`, `RiskControlMapping`, `TraceabilityLink`,
  `SecurityTrace`, or the mixed graph contracts.
- No automated risk scoring, requirement status transition, treatment-plan
  creation, control effectiveness evaluation, or threat lifecycle transition
  from simply viewing the workspace.
- No new security scheme, credential store, config namespace, audit writer,
  error envelope, graph writer, or privileged GitHub side-effect path.
