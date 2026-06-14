# ADR-048: Audit Entity Boundary

## Status

Accepted

## Date

2026-05-18

## Context

GC-U001 requires a first-class `Audit` aggregate. An audit is the governed record of a structured review activity - internal, external, regulatory, or special - producing a scoped, lifecycle-managed, and traceable audit plan and report. Audit activity is the source of `Finding` records, but the two must remain separate: a finding is a deficiency or observation claim with its own lifecycle; an audit is the bounded activity that may produce findings and is also associated with risk records, evidence, and compliance frameworks.

Existing adjacent aggregates that must not be conflated with `Audit`:

- `Finding` captures a specific deficiency or issue claim with severity, remediation tracking, and owner assignment.
- `EvidenceArtifact` captures a single, immutable piece of collected evidence with its own derivation and supersession lifecycle.
- `RiskScenario` and `RiskRegisterRecord` carry risk framing and register governance - not the audit activity itself.
- `domain/audit/` (package) and `/api/v1/audit/**` (path) already belong to the Envers audit trail service; the new aggregate must live at `domain/audits/` and `/api/v1/audits/**` to avoid collisions.

Five link substrates (`AssetLinkTargetType.AUDIT`, `FindingLinkTargetType.AUDIT`, `RiskScenarioLinkTargetType.AUDIT_RECORD`) had `AUDIT` reserved as an unmodeled external placeholder. Until `Audit` exists as a first-class entity, those reverse-lookup paths cannot be made consistent - links referencing audits stored a string in `targetIdentifier` and bypassed project-scoped existence checks.

Without an explicit architectural decision, the likely failure modes are:

- collisions with the Envers `domain/audit/` / `/api/v1/audit/**` surface
- overloading `EvidenceArtifact` or `Finding` with audit lifecycle fields
- leaving `AUDIT` / `AUDIT_RECORD` as external placeholder targets after the entity ships, so reverse lookup and graph traversal remain split between modeled and unmodeled paths
- scattering audit-owned outbound links across `AssetLink`, `FindingLink`, and `RiskScenarioLink` instead of introducing a dedicated `AuditLink` substrate

## Decision

### 1. Audit is a separate aggregate from finding, evidence, and the risk-management cluster

Introduce a dedicated audit aggregate at `domain/audits/` for GC-U001. It must not be implemented by extending the semantics of `Finding`, `EvidenceArtifact`, `Observation`, `RiskScenario`, `RiskRegisterRecord`, `TreatmentPlan`, `Control`, or any existing link entity.

Audits own: audit type (INTERNAL / EXTERNAL / REGULATORY / SPECIAL), scope description, optional objectives list, optional phase timeline (AuditPhase records), optional team members, created-by actor, and lifecycle status (PLANNED → IN_PROGRESS → DRAFT_REPORT → FINAL_REPORT → CLOSED, with a FINAL_REPORT → DRAFT_REPORT rework loop). Linked compliance frameworks, assets, controls, risk records, evidence artifacts, and findings are represented as outbound `AuditLink` edges, not as embedded fields.

Audits must not carry severity ratings, remediation tracking, risk quantification, or evidence derivation semantics. Those remain in their respective aggregates.

### 2. Reuse the established domain and persistence shape

Audit follows the same package shape used by Finding (ADR-038) and other domains:

```text
domain/audits/
    model/
    state/
    service/
    repository/
```

Audit entities must:

- extend `BaseEntity`
- be project-scoped through `Project` and marked `@NotAudited` on the project reference
- use Hibernate Envers for historical state on both the entity and its links
- ship matching Flyway `_audit` tables for both `audit` and `audit_link`

The lifecycle uses the same enum-transition pattern as `FindingStatus`, `TestPlanStatus`, and other state machines. `AuditStatus` declares `validTargets()` and `canTransitionTo()`; the entity's `transitionStatus(AuditStatus)` validates and throws `DomainValidationException` with `current_status` / `target_status` / `valid_targets` detail. Property-based tests cover the full state DAG (L2 per ADR-012).

### 3. Promote AUDIT and AUDIT_RECORD from external to internal in sibling link substrates

Three sibling link substrates carried `AUDIT` / `AUDIT_RECORD` as external placeholder values before this ADR:

- `AssetLinkTargetType.AUDIT` - promoted to internal; `GraphTargetResolverService.validateAssetTarget` now enforces project-scoped existence via `AuditRepository.existsByIdAndProjectId`.
- `FindingLinkTargetType.AUDIT` - promoted to internal; `validateFindingTarget` enforces the same check.
- `RiskScenarioLinkTargetType.AUDIT_RECORD` - promoted to internal (enum value name kept `AUDIT_RECORD`); `validateRiskScenarioTarget` enforces the same check.

`ControlLinkTargetType` and `ThreatModelLinkTargetType` have no `AUDIT` value and are unaffected.

### 4. AuditLink uses the dual-mode link pattern

`AuditLink` follows the same dual-mode shape as `FindingLink` (ADR-038): one of `targetEntityId` (UUID, for internal first-class entities) or `targetIdentifier` (String, for external/framework targets) is populated per row; both unique constraints are enforced at the table level.

`AuditLinkTargetType` includes both internal types (ASSET, CONTROL, RISK_SCENARIO, RISK_REGISTER_RECORD, EVIDENCE, FINDING) and external types (FRAMEWORK, EXTERNAL).

### 5. REST surface at /api/v1/audits/**

The REST surface is `/api/v1/audits/**` (plural) and `/api/v1/audits/{auditId}/links/**`, distinct from the existing `/api/v1/audit/**` Envers trail surface. The controller follows the same shape as `FindingController` and `FindingLinkController`.

### 6. Graph projection contributes AUDIT nodes and edges

`AuditGraphProjectionContributor` implements `GraphProjectionContributor` and contributes AUDIT-typed graph nodes for all audits in a project and AUDIT-typed graph edges for all outbound `AuditLink` rows with internal targets. FRAMEWORK and EXTERNAL links produce no graph edges (same pattern as `FindingGraphProjectionContributor` for OPERATIONAL_ARTIFACT / EVIDENCE links).

Sibling contributors (`AssetGraphProjectionContributor`, `FindingGraphProjectionContributor`, `RiskGraphProjectionContributor`) are updated to emit `GraphEntityType.AUDIT`-typed edges for the promoted `AUDIT` / `AUDIT_RECORD` target types rather than returning null.

## Consequences

- `domain/audits/` is a new bounded context. It may be imported by controllers, sibling link services, and graph contributors but must not import from `api/` or any Spring web layer.
- `/api/v1/audit/**` (Envers service) and `/api/v1/audits/**` (this ADR) coexist; care is needed to not confuse them in routing, test configuration, or path security rules.
- Five Java state enums (`AuditStatus`, `AuditType`, `AuditPhaseKind`, `AuditLinkTargetType`, `AuditLinkType`) must be mirrored in `frontend/src/types/api.ts` (union types and iterable constant arrays for `AuditStatus` and `AuditType`) and `mcp/ground-control/lib.js` (constant arrays for all five).
- `AUDIT` / `AUDIT_RECORD` in three sibling link substrates are now internal targets. Any legacy rows in those tables that carried an `AUDIT`/`AUDIT_RECORD` targetIdentifier string (pre-entity) need data migration if the platform was deployed with those placeholder values in use.
