# GC-M011 Asset Classification and Subtype Extensibility Preflight

Issue: #722
Requirement: GC-M011
Date: 2026-05-16

This is architecture guardrail guidance for implementation of GC-M011. It is not
an implementation plan.

## Boundary

`OperationalAsset` remains the canonical operational asset aggregate. GC-M011
extends how that aggregate is classified and how subtype-specific metadata is
validated and represented. It must not create a second inventory, a CMDB sync
adapter, a generic graph node table, or one entity/table per operational kind.

Keep these concepts separate:

- `AssetType`: top-level operational classification such as service,
  application, infrastructure resource, workload, identity, data asset,
  endpoint, integration, or vendor dependency.
- Subtype: a narrower, project- or catalog-defined specialization under an
  `AssetType`, such as a specific cloud resource, identity kind, endpoint kind,
  data-store kind, or third-party dependency kind.
- Core asset attributes: shared fields on `OperationalAsset` such as UID, name,
  description, ownership/stewardship, environment, criticality, scope, lifecycle,
  and project.
- Subtype metadata: fields that only make sense for a subtype. These may be
  flexible, but they are not a tunnel for core fields, topology, external IDs,
  links, observations, ownership, scope, or lifecycle state.

## Incumbents To Reuse

- Asset aggregate: `OperationalAsset`, `AssetService`, `CreateAssetCommand`,
  `UpdateAssetCommand`, `OperationalAssetRepository`, `AssetController`,
  `AssetRequest`, `UpdateAssetRequest`, and `AssetResponse`.
- Classification vocabulary: `domain/assets/state/AssetType`. It already covers
  the current top-level kinds exposed by the asset API and MCP surface. Do not
  add subtype-specific values to this enum merely to avoid modeling subtypes.
- Asset metadata from GC-M012: owner, `steward`, environment, criticality,
  business context, and scope designation already live on `OperationalAsset` and
  list filtering already uses
  `OperationalAssetRepository.findByProjectIdAndArchivedAtIsNullAndFilters`.
- Topology and references: use `AssetRelation` for asset-to-asset topology,
  `AssetExternalId` for source-system identity, `Observation` for observed
  evidence, and `AssetLink` plus `GraphTargetResolverService` for cross-entity
  targets.
- Structured JSON persistence, where truly needed: use
  `JacksonTextCollectionConverters.StringObjectMapConverter`; do not add
  feature-local `ObjectMapper` parsing or a second JSON serialization convention.
- Graph projection: add asset classification/subtype properties through
  `AssetGraphProjectionContributor`, `GraphEntityType.OPERATIONAL_ASSET`, and
  `GraphIds`; do not write AGE rows directly from asset services.
- API/MCP/docs mirrors: update `docs/API.md`, `mcp/ground-control/lib.js`,
  `mcp/ground-control/index.js`, and frontend API types if the wire contract
  changes. If a backend asset enum is exposed to frontend or MCP, treat the Java
  enum as semantic authority and extend the ADR-034 inventory pattern instead
  of hand-maintaining unchecked mirrors.
- Persistence/audit: use Flyway, Envers audit-table parity, `MigrationSmokeTest`,
  and `RequirementsE2EIntegrationTest` migration version coverage for schema
  changes touching `operational_asset` or related asset tables.

## Cross-Cutting Layers

- Security: `/api/v1/assets/**` stays inside ADR-026/ADR-037's shared
  `ApiPathMatrix`. Bearer traffic passes `IpAllowlistFilter`,
  `BearerTokenAuthFilter`, Spring authorization, and `ActorFilter`; browser
  session traffic passes the browser chain with the same path matrix and CSRF
  rules. Do not add controller-local authorization, actor override fields, new
  role enums, or routes outside `/api/v1/**`.
- Request parsing and validation: Jackson enum binding and Bean Validation own
  DTO shape. Service validation owns same-project checks, subtype/schema
  existence, subtype compatibility with `AssetType`, metadata key/value shape,
  and any clear-vs-assign semantics for nullable fields.
- Metadata schema validation: the repo has profile/schema storage patterns
  (`MethodologyProfile.inputSchema/outputSchema`) but no general JSON Schema
  validator service. If GC-M011 adds schema validation, introduce one reusable
  asset-subtype validation component behind `AssetService`, with one dependency
  and one error-detail shape. Do not duplicate schema checks in controllers,
  DTOs, migrations, MCP handlers, or per-subtype branches.
- Error envelope: use `NotFoundException`, `ConflictException`, and
  `DomainValidationException`; `GlobalExceptionHandler` and
  `shared.web.ErrorResponse` remain the only HTTP error contract. Error detail
  may name the subtype key, schema version, field path, and valid values, but
  must not echo raw subtype metadata, secrets, tokens, or full request bodies.
- Audit and actor provenance: Envers plus `ActorFilter`, `ActorHolder`, MDC, and
  the revision listener provide mutation history. Subtype metadata changes must
  be audited through the same entity/audit-table path; no asset-specific audit
  writer or actor field is needed.
- Observability: rely on `RequestLoggingFilter`, `ActorFilter`, MDC, and
  low-cardinality service logs. Log stable IDs, subtype keys, and schema
  versions when useful; never log raw metadata payloads, tokens, secrets,
  external credential material, or full business context text.
- Configuration and OS/runtime exposure: GC-M011 should not require new secrets,
  shell-outs, subprocess argv, local file reads, or external network calls. If a
  future subtype catalog import or external classifier needs configuration, use
  `@ConfigurationProperties` with startup validation and keep secrets out of
  process arguments, logs, and error envelopes.
- MCP transport: keep `gc_asset` on the existing Zod schema, `pick`,
  `toCamelCase`, `request()`, `addAuthorizationHeader`, `RequestError`, and
  `GROUND_CONTROL_API_TOKEN` / `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN`
  selection. Do not add asset-specific HTTP clients, caller-supplied headers, or
  caller-supplied tokens.
- Tests and policy: controller changes need `@WebMvcTest`; service semantics
  need asset service tests; schema compatibility needs validation tests; graph
  properties need projection tests; migrations need smoke coverage. Run
  `make policy` before completion.

## Extensibility

The primary seam is a stable subtype discriminator plus versioned schema or
profile metadata, scoped at least by top-level `AssetType` and project/catalog
where needed. Adding the next cloud resource, identity kind, endpoint kind, data
asset kind, or vendor dependency subtype should require adding one subtype/schema
definition and mirror tests, not editing controllers, adding another asset
entity, or growing `AssetType` with every vendor-specific resource.

Queryable classification belongs in typed columns, enums, or indexed fields.
Subtype metadata can carry subtype-only detail, but any field that becomes a
common filter, graph facet, access-control input, or workflow join should be
promoted to the canonical asset contract instead of remaining buried in a map.

## Gotchas And Anti-Patterns

- Do not collapse subtype metadata into `AssetType`; `AssetType` is the
  high-level class, not the place for every cloud, endpoint, identity, data, or
  vendor product variant.
- Do not introduce parallel asset records such as `ServiceAsset`,
  `IdentityAsset`, or `VendorDependencyAsset` unless a future ADR proves a
  subtype is a distinct aggregate with independent lifecycle and invariants.
- Do not store core fields, links, relations, external IDs, observations,
  ownership, criticality, scope, or lifecycle state in generic metadata.
- Do not make metadata an unbounded arbitrary JSON escape hatch. Keep it object
  shaped, bounded, serializable, validated, and safe to omit from logs/errors.
- Do not use subtype metadata as an access-control or secret store.
- Do not bypass project-scoped repository methods or resurrect deprecated
  unscoped `AssetService` overloads on new paths.
- Do not add an asset-specific exception hierarchy, error envelope, auth guard,
  audit table pattern, JSON parser, graph materializer, or workflow engine.
- Do not assume persistence alone satisfies schema layering. If schema
  validation is in scope, it needs a canonical validator and tests; if not, say
  so explicitly in the implementing PR.

## Non-Goals

- No CMDB, cloud inventory, or vendor sync adapter.
- No organization-wide taxonomy management UI unless another requirement asks
  for it.
- No graph engine rewrite or AGE-only dependency.
- No RBAC, per-project authorization, or secret-management change.
- No replacement of `AssetRelation`, `AssetExternalId`, `AssetLink`,
  `Observation`, risk/control/finding link surfaces, or traceability links.
- No implementation of GC-M011 in this preflight note.
