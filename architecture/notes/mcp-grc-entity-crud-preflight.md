# MCP GRC Entity CRUD Preflight

Issue: #218
Requirement: GC-L006

This note records architecture guardrails for exposing graph-native GRC entity
CRUD through MCP with REST parity. It is not an implementation plan.

## Boundary

GC-L006 is an MCP adapter parity requirement. The Spring REST API remains the
semantic authority for domain behavior, validation, authorization, persistence,
audit, and graph projection. MCP must not become a second controller layer, a
second DTO hierarchy, or a write path that bypasses domain services.

Use the consolidated MCP surface from ADR-035:

- Named, action-discriminated tools own writes and lifecycle actions:
  `gc_asset`, `gc_observation`, `gc_risk_scenario`, `gc_threat_model`,
  `gc_risk_governance`, `gc_control`, `gc_finding`, `gc_evidence`,
  and `gc_audit`.
- Pure reads, list-by-filter, get-by-id, history, and graph visualization use
  `gc_query` when no curated read action exists.
- Each named tool calls functions in `mcp/ground-control/lib.js`; those
  functions call existing REST endpoints. Do not call repositories, AGE,
  shell commands, or database clients from MCP.

Current REST and MCP surfaces already cover many GC-L006 terms:

- Risks: `RiskScenario`, `RiskRegisterRecord`, `RiskAssessmentResult`,
  and `TreatmentPlan` through `gc_risk_scenario` and `gc_risk_governance`.
- Controls: `Control`, `ControlTest`, and
  `ControlEffectivenessAssessment` through `gc_control`.
- Findings: `Finding` and `FindingLink` through `gc_finding`.
- Evidence artifacts: append-only `EvidenceArtifact` through `gc_evidence`.
- Observations, operational assets, topology relationships, asset links, and
  external ids through `gc_observation` and `gc_asset`.
- Audit activity and framework/evidence/finding/risk/control links through
  `gc_audit`.

Terms that are not currently first-class REST aggregates must not be faked at
the MCP layer:

- Third parties are currently modeled as `OperationalAsset` rows with
  `AssetType.THIRD_PARTY` plus subtype/metadata, links, and external ids. Do
  not introduce a separate vendor CRUD tool unless a backend aggregate/ADR does
  so first.
- Questionnaires do not appear to have a first-class REST aggregate in the
  current codebase. MCP parity cannot create one by storing questionnaire data
  in generic metadata.
- Compliance framework mappings are currently represented as external
  `FRAMEWORK`/mapping fields in audit links and pack/control-pack surfaces,
  not as a universal mapping aggregate. If future REST work promotes them to a
  first-class entity, the MCP surface should mirror that entity then.
- Remediation plans are ambiguous: `TreatmentPlan` is a risk-treatment
  aggregate, while `FindingLinkTargetType.REMEDIATION_PLAN` is still an
  external reference. Do not collapse finding remediation workflow into
  treatment plans or metadata without a backend decision.

## Incumbents To Reuse

- MCP transport: `mcp/ground-control/lib.js` owns `buildUrl`,
  `addAuthorizationHeader`, `request`, `RequestError`, `parseErrorBody`,
  `toCamelCase`, `toSnakeCase`, `pick`, and `reqArg`.
- MCP query escape hatch: `mcp/ground-control/gc-query.js` owns the read-only
  allowlist, denylist, timeout, body cap, and validation error shape.
- MCP write adapters: extracted handlers such as `gc-control.js`,
  `gc-risk-governance.js`, `gc-risk-scenario.js`, `gc-threat-model.js`,
  `gc-finding.js`, `gc-evidence.js`, `gc-audit.js`, and `link-create.js` are
  the canonical style for testable action dispatch and body allowlists.
- REST contracts: request/response records under `backend/src/main/java/.../api`
  plus `@Valid`, Bean Validation, Jackson enum binding, and
  `ProjectService.resolveProjectId/requireProjectId`.
- Domain services and repositories: the domain package owning the entity
  remains the write owner. Services enforce UID uniqueness, same-project
  target checks, state transitions, deletion guards, and append-only rules.
- Link and graph validation: `GraphTargetResolverService`,
  `GraphEntityType`, `GraphIds`, `GraphProjectionContributor` implementations,
  and `GraphTraversalLimits` are the canonical graph-native boundary.
- Structured JSON fields: use existing Jackson text converters such as
  `JacksonTextCollectionConverters`; do not introduce MCP-local JSON parsing or
  a second schema language.
- Errors: use `NotFoundException`, `ConflictException`,
  `DomainValidationException`, `GlobalExceptionHandler`, and `ErrorResponse`.
  MCP must preserve that envelope through `RequestError`.
- Security and audit: reuse `ApiPathMatrix`, bearer-token filters,
  `ActorFilter`, `ActorHolder`, `RequestLoggingFilter`, SLF4J/MDC, and Envers.
- Policy gates: controller changes trigger `run_controller_contracts` and must
  update `docs/API.md`, `mcp/ground-control/lib.js`,
  `mcp/ground-control/index.js`, and the matching `@WebMvcTest`. Enum mirrors
  stay under ADR-034 and `make policy`.

## Cross-Cutting Layers

- MCP process config: continue loading `GC_BASE_URL`,
  `GROUND_CONTROL_API_TOKEN`, and `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN`
  from environment or consumer-repo `.env`. GC-L006 should not add secret
  delivery, token arguments, command-line token passing, or per-tool clients.
- MCP public schema: Zod validates caller-facing shape only. Public args use
  snake_case at the top level. Per-action required fields use `reqArg`;
  request bodies use `pick(args, BODY_FIELDS)` so action/control fields cannot
  leak into REST DTOs. There is one documented current exception: the
  `sources` array on `gc_evidence` accepts nested camelCase fields
  (`sourceKind`, `sourceEntityId`, `sourceIdentifier`, `role`) because the
  adapter passes the array straight through to the backend
  `EvidenceArtifactRequest.sources` DTO instead of running it through
  `toCamelCase`. New adapters should follow the universal snake_case +
  `toCamelCase` path; do not propagate the evidence exception by copying its
  shape, and do not assume `toCamelCase` will rewrite nested array element
  keys in adapters that do follow the universal path.
- Field conversion: ordinary request bodies pass through `toCamelCase`.
  Extend `TO_CAMEL` only for real wire-name differences. Preserve opaque maps
  such as `metadata` and `schema_body`; do not recursively rewrite
  user-defined keys. Adapters that manually shape the body (for example, the
  `toCreateBody` helper in `gc-evidence.js`) bypass this conversion path; if
  you add such a helper, leave a one-line comment naming the backend DTO
  field whose shape the manual mapping mirrors.
- URL construction: named tools use fixed `/api/v1/...` paths through
  `request`. Ad-hoc reads use `gc_query`, which must remain GET-only,
  relative-path-only, allowlisted, denylisted, parameterized through flat
  `params`, timeout-capped, and body-capped.
- Header and auth forwarding: callers never supply headers. `request` and
  `gc_query` set `X-Actor: mcp-server`, `Accept`/`Content-Type` as needed, and
  use `addAuthorizationHeader` for bearer token routing.
- Backend security: every REST call still passes the shared path matrix,
  IP allowlist where configured, bearer authentication, role authorization,
  and actor provenance. MCP catalog visibility is not permission.
- Backend validation: DTO Bean Validation, Jackson enum binding, service
  semantic checks, `AssetSubtypeValidator`, append-only evidence rules,
  lifecycle state machines, and `GraphTargetResolverService` remain
  authoritative. MCP validation is caller ergonomics and request shaping.
- Error envelopes: backend non-2xx responses flow through `ErrorResponse` and
  `RequestError`. Do not return raw fetch errors, stack traces, response
  headers, bearer tokens, or raw request bodies to the LLM.
- OS/runtime exposure: GC-L006 should not require subprocesses, shelling out,
  local file reads, absolute URLs, alternate hosts, or argv-visible secrets.
- Observability: keep request-level logging and actor context shared. Domain
  services may log low-cardinality lifecycle events, but must not log evidence
  summaries, questionnaire answers, metadata payloads, bearer tokens, or large
  free-text bodies.

## Extensibility

The extension seam is a data-driven entity inventory for the consolidated MCP
adapter: entity name, REST path family, read path prefix, actions,
body-field allowlists, required fields, enum mirrors, and handler module. The
next GRC entity should require adding one inventory entry plus a focused
handler/test when behavior is non-trivial, not cloning a new five-tool CRUD
family or adding a generic write proxy.

For link target graduation, extend the existing resolver and graph projection
seams. Two distinct cases use this seam, and the note keeps them separated so
that future readers do not conflate "no aggregate exists" with "aggregate
exists but graph projection lags":

- **Aggregate does not yet exist.** When `FRAMEWORK`, `QUESTIONNAIRE`, or a
  first-class third-party / vendor record (distinct from
  `AssetType.THIRD_PARTY`) becomes a first-class aggregate, update the
  relevant target enum, `GraphTargetResolverService`, the graph contributor,
  REST DTOs, MCP enum mirror, and adapter tests together. These three targets
  are tracked respectively by GC-L011, GC-L010, and GC-L009.
- **Aggregate exists but link-target projection lags.** `EVIDENCE` already
  has a first-class backend aggregate (`EvidenceArtifact`, ADR-045), and
  `gc_evidence` already exposes it via MCP; the gap is that the wider graph
  projection still treats `EVIDENCE` (and the ambiguous remediation-plan
  link target) as external in resolver and contributor logic in several
  backend files. That gap is out of GC-L006's MCP-CRUD scope, is not fixed
  here, and is surfaced on the GitHub issue thread for #218 so a follow-up
  graph-projection requirement can pick it up explicitly.

The general rule remains: update target enum, resolver, projection
contributor, REST DTOs, MCP enum mirror, and adapter tests together; do not
land any of those changes in isolation.

## Gotchas And Anti-Patterns

- Do not create a generic `gc_call_api` write tool. It would bypass the curated
  action/field allowlists that keep MCP from forwarding arbitrary methods,
  headers, bodies, and stale DTO fields.
- Do not duplicate backend request records as an independent MCP schema
  hierarchy. MCP mirrors the REST contract; backend DTOs and services are the
  source of truth.
- Do not use `metadata` as a tunnel for missing first-class fields such as
  questionnaire answers, compliance mappings, remediation status, or vendor
  risk decisions.
- Do not conflate observations, evidence artifacts, findings, audits,
  treatment plans, and control tests. These are separate aggregates with
  different lifecycles and audit semantics.
- Do not turn append-only evidence into generic update/delete CRUD for the sake
  of "full CRUD"; feature parity means respecting the REST contract, including
  intentionally absent update/delete actions.
- Do not hide missing backend capability behind MCP-only behavior. If REST has
  no aggregate or route, GC-L006 cannot satisfy parity by inventing MCP-only
  persistence.
- Do not widen `gc_query` for writes, absolute URLs, caller-supplied headers,
  nested params, query strings embedded in paths, or Cypher passthrough.
- Do not add controller-local auth, exception handlers, object mappers, graph
  writers, or audit tables to compensate for MCP needs.
- Do not leave promoted first-class link targets as external string
  identifiers after their aggregate lands. Update resolver and graph projection
  in the same change.

## Scope Decomposition

GC-L006's original statement enumerated eleven entity terms. Empirical inventory
against the current codebase (issue #218) showed eight terms were delivered by
existing MCP tools and three terms had no first-class REST aggregate to expose.
Because MCP parity cannot be satisfied against a missing REST aggregate (see
the Boundary section), the three undelivered terms were split into their own
requirements rather than blocking GC-L006 on backend decisions that themselves
have no requirement yet:

- **GC-L009—MCP Third-Party / Vendor Aggregate** (Wave 5, MUST, INTERFACE,
  DRAFT). Covers MCP exposure for a future first-class vendor / third-party
  aggregate. Today, third-party records are subsumed under
  `AssetType.THIRD_PARTY` on `gc_asset`; GC-L009 picks up when the backend
  promotes a standalone vendor aggregate.
- **GC-L010—MCP Questionnaires** (Wave 5, MUST, INTERFACE, DRAFT). Covers
  MCP CRUD and lifecycle operations on a future questionnaire aggregate
  (definitions, responses, scoring). No backend questionnaire entity exists
  today; GC-L010 picks up when the backend aggregate lands.
- **GC-L011—MCP Compliance Framework Mapping Aggregate** (Wave 5, MUST,
  INTERFACE, DRAFT). Covers MCP CRUD and traversal for a future first-class
  compliance-framework-mapping aggregate. Today, framework mappings exist only
  as external `FRAMEWORK` link types on `gc_audit` and as control-pack
  framework reference fields. GC-L011 picks up when the backend promotes
  framework mappings to a first-class aggregate (which also requires updating
  link-target enums, `GraphTargetResolverService`, and graph contributors per
  the Extensibility seam above).

GC-L006's amended statement reflects the narrowed scope (the eight delivered
terms) and points at GC-L008 (Asset and Observation MCP Operations) for the
deliberate overlap on the asset/observation/topology/evidence substrate. The
amendment is recorded against the requirement's audit history; the original
statement is preserved by Envers for traceability.

## Non-Goals

- No implementation of GC-L006 in this preflight.
- No new backend aggregate, migration, controller, repository, state machine,
  graph projection, or MCP tool registration solely from this note.
- No change to ADR-026 security semantics, ADR-035 tool consolidation,
  ADR-034 enum mirror policy, or the repo workflow gates.
- No OpenAPI/codegen project, new MCP runtime, direct database access from MCP,
  or new secret-management mechanism.
- No attempt to decide the future data model for questionnaires, compliance
  framework mappings, remediation-plan aggregates, or vendor/third-party
  management beyond preserving the boundaries above. The MCP work for those
  aggregates is tracked in GC-L009, GC-L010, and GC-L011 respectively, gated
  on a corresponding backend aggregate decision.

## Update: GC-L011 Resolved (issue #744, 2026-05-30)

The compliance-framework-mapping promotion path described above has shipped
as GC-I002 / GC-I005 / GC-I007 / GC-L011 (issue #744). See
`architecture/notes/compliance-framework-aggregate-preflight.md` for the
boundary decisions; the MCP parity tracked by GC-L011 lands in the same PR
as the backend aggregate so the link-target enums, graph projection
contributor, and MCP enum mirror move together. GC-L009 (third-party
aggregate) and GC-L010 (questionnaires) remain DRAFT pending corresponding
backend aggregate decisions.
