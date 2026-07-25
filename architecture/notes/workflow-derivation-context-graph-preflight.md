# Workflow and Derivation Context-Graph Preflight

Issue: #1311
Requirement: none

This note records the architecture disposition for adding workflow and
derivation concepts to the mixed context graph. It is not an implementation
plan.

## Preflight Verdict

Issue #1311 is blocked as written because two assumptions in its July 10 scope
were superseded before this preflight:

- ADR-089 and migration V199 retired the derivation-run, boundary-model, and
  architecture-model product aggregates, including their runtime entities,
  repositories, APIs, tables, and audit tables. A contributor over those
  concepts would recreate a retired product surface through the graph and would
  have no live relational source of truth.
- Issue #1359 superseded ADR-081 and removed the Temporal workflow-execution
  model. There is no current first-class `WorkItem` or workflow-execution
  aggregate. The surviving `WorkflowRun` and `WorkflowPhaseEvent` types belong
  to ADR-061's reporting model and must not be described as the removed Temporal
  engine entities.

The issue body's July 10 removal of contracts and invariants remains binding;
the stale word "contract" in the issue title is not scope.

Do not implement a derivation, boundary-snapshot, architecture-model, contract,
workflow-execution, or first-class work-item contributor under this issue. A
future product decision may revive one of those aggregates, but that decision
must restore an authoritative relational model and its security, lifecycle,
audit, and API boundaries before graph projection work begins.

## Surviving Workflow Boundary

The only live candidate is the ADR-061 reporting model:
`WorkflowRun` plus append-only `WorkflowPhaseEvent`, populated from the durable
ADR-029 issue-thread records. It remains a reporting projection and never
becomes a gate state machine, execution authority, retry controller, or
replacement for the issue thread merely because another read-side projection
is built from it.

Even that candidate is blocked pending an explicit time-spine decision.
ADR-061 deliberately made both tables unaudited, while ADR-084 and
`GraphProjectionContributorAuditGuardTest` require every contributor's backing
entity to be `@Audited`. `AgeGraphService` records the Envers revision visible
to each graph snapshot; projecting mutable unaudited telemetry would make that
`source_revision` claim false.

If workflow telemetry is retained in #1311, the required decision is to amend
ADR-061 so `WorkflowRun` and `WorkflowPhaseEvent` participate in Envers, with
forward-only audit-table migrations. The audit-storage cost is the price of
joining the revision-addressed context graph. Do not weaken or special-case the
audit guard, fabricate revisions, use `createdAt`/`occurredAt` as a second time
spine, or materialize on every telemetry write as a substitute for auditability.
If that audit decision is not accepted, workflow telemetry stays out of the
context graph.

## Conditional Graph Shape

After the audit decision, use the existing mixed-graph vocabulary and
projection boundary:

- A `WORKFLOW_RUN` node represents the persisted ADR-061 run row.
- A GitHub issue work item is an identifier-addressed reference derived only
  from the exact persisted `(project, repo, issueNumber)` tuple; it is not a new
  JPA aggregate and must not be called a first-class work item. Give the
  workflow family its own work-item-reference classification rather than
  broadening or conflating the requirements-specific `ARTIFACT_REFERENCE`
  meaning. Extend `GraphIds` with a bounded, length-framed digest of the tuple;
  do not expose a raw repository name in the graph node id.
- Each persisted `WorkflowPhaseEvent` is an event edge from its run to that
  work-item reference. The edge id is the event UUID; `phase`, `eventType`,
  `cycleIndex`, `occurredAt`, bounded `durationMs`, `outcome`, and `provenance`
  remain event properties. This avoids inventing a separate gate aggregate or
  mistaking `PhaseEventType` for a lifecycle state machine. Runs without an
  complete repository-and-issue identity remain isolated run nodes rather than
  receiving a fabricated work item or self-loop.
- A separate run-to-work-item edge records the stable association even when no
  phase event has been ingested. Its meaning and the phase-event meaning must
  receive distinct controlled terms; neither is a generic `ASSOCIATED` or
  `DEPENDS_ON` alias.

This edge-based event shape keeps append-only operational history out of the
5,000-node budget, but the 20,000-edge cap still applies. Do not silently
truncate history or add a wall-clock `now()` filter: time-based membership could
change without a new Envers revision and would again falsify snapshot time. If
real data approaches the edge cap, the extension seam is an explicit,
revision-stable projection-scope contract or a separately decided telemetry
retention policy, not an ad hoc limit inside the contributor.

## Incumbents and Cross-Cutting Gates

- `WorkflowRunRepository` and `WorkflowPhaseEventRepository` own project-scoped
  reads. Resolve the graph's project UUID to the persisted immutable project
  identifier in the query boundary; never load all telemetry and filter in
  memory. Preserve the run/event project-match invariant so cross-project or
  dangling event edges are absent.
- `workflow-run-record.v1.schema.json`, the existing REST request DTOs, and
  `WorkflowTelemetryService` remain the write-shape and semantic-validation
  authorities. The contributor reads their persisted result; it does not add a
  graph ingestion DTO, duplicate schema, or second reserved-marker validator.
- `GraphEntityType`, `GraphIds`, `GraphNode`, `GraphEdge`,
  `GraphProjectionContributor`, and `GraphProjectionRegistryService` are the
  projection model. No direct AGE write belongs in `WorkflowTelemetryService`,
  a controller, or an ingestion adapter.
- `AgeGraphService` remains the only SQL/Cypher and snapshot-publication
  boundary. Every new property key must join
  `APPROVED_PROPERTY_KEYS`; labels and edge types are closed vocabulary; all
  row values remain bound parameters. `GraphTraversalLimits` continues to
  bound filters, identifiers, projections, paths, and depth.
- The workflow-and-process family, controlled terms, `GraphEntityType` values,
  and contributor edge literals must be registered together in the three
  `contracts/ontology/` artifacts. The inventory-driven ontology policy must
  pass in both directions. Do not add a second registry or repeat term
  definitions in contributor code.
- Graph reads stay on the existing project-scoped `/api/v1/graph/**` routes and
  pass through `ProjectService`, `ApiPathMatrix`, the bearer/session chains, and
  the IP allowlist. Materialization stays under the existing admin route. No new
  controller, endpoint, or authorization exception is needed.
- Reuse the existing `@Valid` graph request DTOs and
  `MixedGraphService`'s defensive parsing. Unknown entity filters, oversized
  projections, invalid roots, and missing projects continue through
  `DomainValidationException`/`NotFoundException`, `GlobalExceptionHandler`, and
  `ErrorResponse`.
- Project only the ADR-061 closed, redacted scalar set needed for graph identity
  and traversal. Do not project prompts, issue bodies, reviewer payloads,
  secrets, bearer/provider credentials, local telemetry files, or arbitrary
  source text. The reserved-marker validation on ingestion remains
  authoritative; projection is read-only and does not duplicate it.
- Envers revisions inherit actor context through the existing
  `ActorFilter`/`ActorHolder` path. Keep observability on SLF4J with bounded
  identifiers and aggregate counts; do not log per-event outcome text, repo or
  branch content, raw properties, SQL, or Cypher.
- The existing `gc_graph` MCP tool already reaches the mixed graph. Its
  `entity_types` input remains a bounded string list whose values the backend
  validates; do not add an MCP enum mirror, GitHub fetch, shell command, or
  privileged side effect to populate or query this projection.
- `GraphEntityType` is already in the ADR-034 generated-enum inventory. Refresh
  OpenAPI and generated TypeScript through `make contracts`; update the
  frontend color and tooltip coverage that iterates `GRAPH_ENTITY_TYPES`.
  Never hand-edit generated contracts or add a parallel frontend enum.

No new environment variable, `@ConfigurationProperties` type, secret, network
client, filesystem scan, process-argv value, background scheduler, or OS service
is required. AGE remains derivative and is rebuilt through the existing
admin-only materialization operation.

## Verification Guardrails

Contributor tests must cover project isolation, work-item-reference
deduplication, same-number issues in different repositories, runs with partial
work-item identity, multiple events between the same endpoints, exact event
direction and identity, and the closed redacted property set. The existing
audit-contributor guard must pass without exceptions. AGE tests must pin every
emitted property key and one integration test must
materialize and read a workflow run plus repeated phase events. Ontology,
generated-enum, frontend color/tooltip, contract, ArchUnit, and repository
policy gates remain part of the same change.

## Non-Goals and Anti-Patterns

- No resurrection of the ADR-089 GRC composition or the removed Temporal lane.
- No graph-owned workflow, derivation, boundary, architecture, contract, or
  work-item table.
- No `WorkflowRun`/`WorkflowPhaseEvent` rename into execution-authority concepts.
- No second workflow-event schema, validator, exception hierarchy, error
  envelope, projection registry, graph endpoint, or graph store.
- No weakening of Envers snapshot semantics, project scoping, AGE identifier
  allowlists, parameter binding, traversal caps, or the ontology binding gate.
- No inference from GitHub, issue bodies, `.gc/telemetry`, repository files, or
  historical dropped tables during materialization.
