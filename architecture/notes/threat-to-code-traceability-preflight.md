# Threat-to-Code Traceability Preflight

Requirement: GC-H003
Issue: #735

This is architecture guardrail guidance for threat-to-code traceability. It is
not an implementation plan.

## Boundary

GC-H003 is an end-to-end read and linkage requirement over existing security,
asset, requirement, control, and artifact concepts. It should not introduce a
new all-purpose "threat-to-code" aggregate.

- `ThreatModel` remains the upstream threat-model entry.
- `RiskScenario` remains the scoped risk/loss scenario.
- `OperationalAsset`, including `AssetType.BOUNDARY`, remains the structured
  affected service, identity, repository, configuration boundary, or trust
  boundary context.
- `Requirement` and `TraceabilityLink` remain the requirement-to-artifact
  contract for implementing code, config, tests, proofs, documentation,
  GitHub issues, and controls.
- `Control`, `ControlLink`, `ControlTest`, and
  `ControlEffectivenessAssessment` remain the mitigation and verification
  records.

The canonical write paths are already present: threat-owned links,
risk-scenario-owned links, asset links, control links, requirement relations,
and requirement traceability links. GC-H003 should compose them into a
project-scoped trace view instead of copying their facts into a parallel table.

## Incumbents To Reuse

- ADR-024 and `ThreatModelLinkService` for threat-model-owned links.
- `RiskScenarioLinkService` for risk-scenario-owned links.
- ADR-019/020, `OperationalAsset`, `AssetRelation`, and `AssetLink` for
  affected assets, identities, boundaries, and asset-anchored traversal.
- ADR-011, `TraceabilityLink`, `TraceabilityService`, `ArtifactType`, and
  `LinkType` for requirement-to-code/config/issue/control artifact evidence.
- `ControlLinkService`, `ControlTestService`, and
  `ControlEffectivenessAssessmentService` for mitigation and verification
  context.
- `GraphTargetResolverService` for project-scoped internal target validation;
  use `targetEntityId` for first-class targets and `targetIdentifier` only for
  external or not-yet-modeled targets.
- Mixed graph projection via `GraphEntityType`, `GraphIds`,
  `GraphProjectionContributor`, and `MixedGraphService`. JPA remains the
  source of truth.
- MCP/frontend/API mirrors: `mcp/ground-control/lib.js`, `link-create.js`,
  `frontend/src/types/api.ts`, graph constants, and `docs/API.md`.

## Cross-Cutting Layers

- Security: any REST surface stays under `/api/v1/**` and passes the shared
  bearer/browser path matrix (`IpAllowlistFilter`, `BearerTokenAuthFilter`,
  Spring authorization / session auth, then `ActorFilter`). Do not add
  route-local auth, actor fields, token fields, or a privileged direct GitHub
  call path.
- Request parsing and validation: Jackson enum binding and Bean Validation own
  DTO shape. Services own same-project checks, duplicate detection,
  internal-vs-external target shape, traversal bounds, and lifecycle rules. Do
  not duplicate `GraphTargetResolverService` in controllers, MCP tools, or
  frontend code.
- Error envelopes: use `NotFoundException`, `ConflictException`, and
  `DomainValidationException` through `GlobalExceptionHandler` and
  `ErrorResponse`. Errors may name stable ids, types, and fields, but must not
  echo threat narratives, exploit text, config values, file contents,
  Authorization headers, bearer tokens, stack traces, or raw request bodies.
- Audit and observability: mutations continue through audited entities and
  Envers. Actor provenance comes from `ActorFilter`, `ActorHolder`, and
  `GroundControlRevisionListener`. Logs use SLF4J with stable ids and
  low-cardinality enums, not raw narratives, evidence payloads, or config
  content.
- Persistence: prefer no new tables for GC-H003. If a persisted artifact is
  genuinely needed, use Flyway, audit-table parity where the entity is audited,
  project-scope indexes, reverse-lookup indexes, and `MigrationSmokeTest`
  updates.
- Config and OS/runtime exposure: GC-H003 should require no new secrets,
  environment bindings, subprocesses, repo filesystem scans, GitHub CLI calls,
  or network clients. Future importers must use `@ConfigurationProperties`
  with startup validation and keep tokens out of argv, logs, persisted
  narratives, and error envelopes.
- Policy and workflow: API-visible enum or DTO changes must update backend
  tests, MCP constants/tests, frontend types/constants, docs, and the ADR-034
  inventory if the enum is part of that checked contract. Run `make policy`
  before declaring the work complete.

## Extensibility

The extension seam is a project-scoped trace query/read model parameterized by
source kind (`THREAT_MODEL` or `RISK_SCENARIO`), source id or UID, target type
filters, artifact filters (`ArtifactType` / `LinkType`), and traversal depth.

The mixed graph can answer first-class internal paths, but it does not project
external code/config/issue leaves today: `TraceabilityLink` artifacts and
external `targetIdentifier` links are terminal artifact references. A GC-H003
view must either include those terminal artifact leaves from the existing link
rows or deliberately add a derived artifact projection from those rows. It must
not silently report an incomplete path just because external artifacts are not
graph nodes.

Adding future first-class targets such as repository records, scanner findings,
architecture model artifacts, remediation plans, or audit artifacts should
extend the target enum, `GraphTargetResolverService`, graph contributor,
MCP/frontend mirrors, and tests. It should not require a new resolver family, a
new graph writer, or a second traceability schema.

## Gotchas And Anti-Patterns

- Do not create a generic `ThreatToCodeTrace`, `SecurityTraceLink`, or
  all-purpose graph-edge table when the existing anchored link surfaces already
  own the facts.
- Do not use raw UIDs or identifiers for first-class assets, requirements,
  controls, threat models, risk scenarios, findings, evidence, observations, or
  audits when a project-scoped UUID target exists.
- Do not weaken `TraceabilityService`'s `IMPLEMENTS`-requires-`ACTIVE`
  requirement rule to make DRAFT threat work look complete.
- Do not rely on unscoped reverse lookups such as artifact-only traceability
  queries for project-scoped security assurance views; add or use
  project-aware queries.
- Do not require callers to create duplicate links on threat, risk, asset,
  control, and requirement surfaces for the same semantic fact unless one
  service owns mechanical consistency.
- Do not store asset or boundary context only in free text when an
  `OperationalAsset` exists.
- Do not write AGE rows directly from controllers or services, add
  feature-specific graph materialization, or scan a repository filesystem to
  infer code links.
- Do not add duplicate exception hierarchies, validators, auth filters, audit
  writers, JSON schema systems, workflow engines, MCP serializers, or frontend
  enum mirrors.

## Non-Goals

- No implementation of GC-H003 in this preflight.
- No replacement of `ThreatModel`, `RiskScenario`, `OperationalAsset`,
  `Requirement`, `Control`, `TraceabilityLink`, `AssetLink`,
  `ThreatModelLink`, `RiskScenarioLink`, or `ControlLink`.
- No automatic risk scoring, treatment-plan creation, control-effectiveness
  rating, requirement status transition, threat lifecycle transition, or
  mitigation claim from the presence of a path.
- No scanner, ticketing, GitHub, repository-indexing, code-search, or external
  threat-intelligence integration.
- No new security scheme, config surface, audit writer, error envelope, graph
  writer, or MCP transport helper.
