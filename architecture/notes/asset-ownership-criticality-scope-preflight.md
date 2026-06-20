# GC-M012 Asset Ownership, Criticality, and Scope Preflight

Date: 2026-05-16

## Boundary

GC-M012 extends the existing operational asset aggregate. Ownership, stewardship,
environment, criticality, business or mission context, and assurance scope are
metadata about `OperationalAsset`; they must not create a second asset inventory,
assurance-scope aggregate, person/team directory, or reporting-only shadow table.

Use the existing `domain/assets/` stack:

- `OperationalAsset` as the persisted source of truth.
- `AssetService` as the mutation and project-membership gate.
- `OperationalAssetRepository` for project-scoped query methods.
- `AssetController` plus `AssetRequest`, `UpdateAssetRequest`, and
  `AssetResponse` for the REST surface.
- `AssetGraphProjectionContributor` for graph/reporting properties.
- `AssetLink`, `ControlLink`, `RiskScenarioLink`, and `FindingLink` for
  workflow relationships to controls, risk, findings, audits, evidence, and
  other assurance entities.

## Guardrails

- Every read and write path remains project-scoped. New repository lookups and
  filters must include `projectId`; by-ID routes must continue to fail closed
  through `ProjectService.requireProjectId(...)` and `AssetService.getById(projectId, id)`.
- Use Bean Validation on API records and domain validation in `AssetService`.
  Invalid enum values should keep flowing through Jackson and
  `GlobalExceptionHandler`'s standard `validation_error` envelope.
- Keep the fields audited with the existing Envers pattern. A migration that
  changes `OperationalAsset` must update `operational_asset_audit` and
  `MigrationSmokeTest`.
- If new API-exposed enums are added for environment, criticality, or scope,
  mirror them in `frontend/src/types/api.ts` and `mcp/ground-control/lib.js`.
  ADR-034's inventory pattern is the right extension point if they become
  contract-critical enough to gate with `make policy`.
- Queryability belongs on the asset list/query surface and the mixed graph
  projection. Do not make risk, control, audit, or reporting workflows store
  duplicate owner/criticality/scope snapshots unless a future requirement
  explicitly asks for historical denormalization.
- Keep scope designation distinct from requirement status, quality-gate
  `scopeStatus`, control `implementationScope`, and risk
  `assetScopeSummary`. GC-M012 scope is assurance inclusion metadata for an
  asset, not lifecycle state or narrative scope prose.

## Concept Boundaries

- `owner`: accountable party for the asset. Existing owner fields on controls,
  findings, risk register records, and treatment plans are workflow-owner fields;
  they are not canonical asset ownership.
- `steward`: operational custodian. Do not overload `owner` to carry
  both accountability and custody.
- `environment`: deployment or operational environment for the asset. Do not
  infer environment from UID, name, external IDs, or source-system strings.
- `criticality`: asset importance classification. Do not reuse finding severity,
  risk level, control effectiveness, or assurance confidence values.
- `business/mission context`: descriptive context on the asset. Keep structured
  links to risks, controls, findings, evidence, and audits in the existing link
  tables rather than embedding those relationships in this text.
- `scope`: in-scope / out-of-scope assurance designation for the asset. It is
  not archive state; archived assets should remain excluded by existing active
  asset queries unless a caller explicitly asks for archived records in a future
  change.

## Cross-Cutting Layers

- Security: `/api/v1/assets/**` remains under the shared API path matrix from
  ADR-026/ADR-037. No controller-local authorization checks or new security
  chain should be introduced.
- Audit identity: mutations flow through `ActorFilter -> ActorHolder -> Envers`;
  no custom audit actor or ad hoc history table is needed.
- Error handling: use existing `DomainValidationException`, `ConflictException`,
  and `NotFoundException`; do not add a new asset exception hierarchy.
- Logging: rely on the existing request logging and MDC context. Do not log raw
  request bodies or newly added business context text.
- Persistence: use Flyway migrations and JPA columns on `OperationalAsset` plus
  matching audit columns. Avoid JSON blobs for fields that must be filterable.
- Workflow/MCP/frontend: asset DTO changes require API docs, matching
  `@WebMvcTest`, frontend type updates, and MCP camel/snake-case mappings.

## Extensibility Seam

The immediate seam is an asset filter surface parameterized by optional owner,
`steward`, environment, criticality, and scope-designation criteria. That keeps
risk, control, audit, graph, and reporting callers using one project-scoped
asset query contract instead of each workflow inventing its own lookup.

If a future requirement needs organization-defined vocabularies for criticality
or environment, introduce that as a dedicated taxonomy/configuration feature
then. GC-M012 should not smuggle a taxonomy subsystem into the asset metadata
change.

## Non-Goals

- No RBAC or per-project authorization model changes.
- No CMDB sync adapter or source-system import pipeline.
- No user/team directory for owners or stewards.
- No new risk, control, audit, or reporting aggregate.
- No graph engine rewrite or AGE-only dependency.
- No attempt to backfill semantics from free-text existing records.
