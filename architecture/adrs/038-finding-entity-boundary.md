# ADR-038: Finding Entity Boundary

## Status

Accepted

## Date

2026-05-13

## Context

GC-V001 requires a unified first-class `Finding` aggregate. A finding is the durable GRC issue record produced by audit activity, control testing, policy evaluation, vulnerability analysis, exception review, and other assessment workflows. The architecture preflight for issue #279 enumerated the failure modes the implementation must avoid (see `architecture/notes/finding-entity-preflight.md`).

Existing adjacent aggregates are easy to conflate with `Finding`:

- `Observation` captures raw or analyst-supplied evidence about an asset, not a governed deficiency.
- `Control` captures the expected safeguard, not a deficiency claim against it.
- `RiskScenario`, `RiskRegisterRecord`, `RiskAssessmentResult`, and `TreatmentPlan` carry risk framing, assessment, register governance, and treatment decisions - not the finding statement itself.
- `ThreatModel` carries upstream threat-analysis context.

Three link substrates (`AssetLinkTargetType`, `ControlLinkTargetType`, `RiskScenarioLinkTargetType`) already reserved `FINDING` as an unmodeled placeholder target type - `AssetLink`, `ControlLink`, and `RiskScenarioLink` rows referencing findings stored a string in `targetIdentifier` and bypassed project-scoped existence checks. Until `Finding` exists as a first-class entity, those reverse-lookup paths cannot be made consistent.

Without an explicit architectural decision, the likely failure modes are:

- overloading `Observation` with finding fields (severity, lifecycle, owner, due date)
- storing affected entities only as free text in a generic `metadata` blob
- duplicating the link substrate by scattering finding-owned outbound links across `AssetLink`, `ControlLink`, and `RiskScenarioLink`
- leaving `FINDING` as an external placeholder target after the entity ships, so reverse lookup and graph traversal remain split between modeled and unmodeled paths

## Decision

### 1. Finding is a separate aggregate from observation, control, and the risk-management cluster

Introduce a dedicated finding aggregate at `domain/findings/` for GC-V001. It must not be implemented by extending the semantics of `Observation`, `Control`, `RiskScenario`, `RiskAssessmentResult`, `RiskRegisterRecord`, `TreatmentPlan`, `ThreatModel`, or any existing link entity.

Findings own: finding type (audit-finding, control-deficiency, policy-violation, vulnerability, exception-escalation), severity, description, root-cause analysis, status, owner, and due date. Affected controls, risks, assets, observations, evidence, audits, and remediation plans are represented as outbound link edges, not as embedded fields.

Findings must not carry quantified risk fields, treatment approval state, or risk-register ownership semantics. Those remain in the risk aggregates.

### 2. Reuse the established domain and persistence shape

Finding follows the same package shape used by other domains:

```text
domain/findings/
    model/
    state/
    service/
    repository/
```

Finding entities must:

- extend `BaseEntity`
- be project-scoped through `Project` and marked `@NotAudited` on the project reference
- use Hibernate Envers for historical state on both the entity and its links
- ship matching Flyway `_audit` tables for both `finding` and `finding_link`

The lifecycle uses the existing simple enum-transition pattern already used in `requirements`, `controls`, `riskscenarios`, and `threatmodels`. `FindingStatus` declares `validTargets()` and `canTransitionTo()`; the entity's `transitionStatus(FindingStatus)` validates and throws `DomainValidationException` with `current_status` / `target_status` / `valid_targets` detail. Property-based tests cover the full state DAG (L2 per ADR-012).

The lifecycle DAG is:

```text
OPEN
  → REMEDIATION_IN_PROGRESS
       → REMEDIATION_COMPLETE
            → VERIFIED_CLOSED   (terminal)
            → REMEDIATION_IN_PROGRESS   (verification rejected; reopen path)
```

`VERIFIED_CLOSED` is terminal - reopening a verified-closed finding creates a new finding rather than reanimating the closed record.

### 3. Finding-owned outbound links use one anchored link mechanism

Finding outbound links live on a finding-owned `FindingLink` entity, anchored on the finding. They are not retrofitted into `TraceabilityLink`, `AssetLink`, `ControlLink`, `RiskScenarioLink`, or `ThreatModelLink`.

`FindingLink` follows the current dual-mode target convention:

- `targetEntityId` carries the UUID of an internal first-class entity, validated through `GraphTargetResolverService.validateFindingTarget`
- `targetIdentifier` carries an external/not-yet-modeled artifact reference, validated only as non-blank

Internal target types: `CONTROL`, `RISK_SCENARIO`, `ASSET`, `OBSERVATION`. External target types: `OPERATIONAL_ARTIFACT`, `EVIDENCE`, `AUDIT`, `REMEDIATION_PLAN`, `EXTERNAL`. When those external target types graduate to first-class aggregates in a later wave, the change is a one-place edit on `validateFindingTarget` + `FindingGraphProjectionContributor` - exactly the conversion this ADR applies to the inbound `FINDING` placeholders below.

Link types (`FindingLinkType`) describe the semantic role of the edge: `AFFECTS`, `CAUSED_BY`, `MITIGATED_BY`, `EVIDENCED_BY`, `OBSERVED_IN`, `REMEDIATED_BY`, `ASSOCIATED`.

### 4. Inbound `FINDING` placeholders become internal modeled targets

Now that `Finding` exists, the `FINDING` constant in `AssetLinkTargetType`, `ControlLinkTargetType`, and `RiskScenarioLinkTargetType` transitions from `externalTarget(...)` to `internalTarget(..., findingRepository.existsByIdAndProjectId(...))` in `GraphTargetResolverService`. The corresponding `*GraphProjectionContributor` switches map `FINDING` to a real `GraphEntityType.FINDING` edge instead of returning `null`.

The conversion is dispositive: leaving `FINDING` as an external target after the entity ships would re-introduce the inconsistency between modeled and unmodeled traversal that this ADR exists to close.

`Finding.delete()` rejects deletion with `ConflictException` (`finding_referenced`) while any `AssetLink`, `ControlLink`, or `RiskScenarioLink` still references the finding by `targetEntityId` - mirrors the reverse-link guard on `ThreatModel.delete()` and prevents orphan inbound edges from surviving a delete.

### 5. Graph integration reuses the mixed-graph projection path

JPA remains the source of truth. Finding nodes and outbound link edges flow into the existing mixed-graph projection via a dedicated `FindingGraphProjectionContributor`, registered through Spring's `GraphProjectionContributor` discovery. `GraphEntityType.FINDING` extends the existing enum.

`contributeNodes` returns every finding regardless of status - `VERIFIED_CLOSED` findings stay in the graph as historical evidence so inbound `AssetLink` / `ControlLink` / `RiskScenarioLink` edges to them never dangle. `contributeEdges` filters outbound edges to archived `ASSET` or `ARCHIVED` `RISK_SCENARIO` targets, mirroring `ThreatModelGraphProjectionContributor`.

No bespoke finding graph endpoint, no direct AGE writes from controllers or services.

### 6. Validation, exception handling, and observability stay shared

Finding API requests carry only shape validation at the DTO boundary (`@NotBlank`, `@Size`, `@NotNull`, Jackson enum parsing). Semantic validation - project scope, status transition validity, UID uniqueness, link target existence - lives in the service layer and uses the existing exception hierarchy (`DomainValidationException`, `ConflictException`, `NotFoundException`). HTTP mapping continues through `GlobalExceptionHandler` and `ErrorResponse`.

Error envelopes may reference field names and structured detail (`field`, `current_status`, `valid_targets`) but must not echo `description` or `rootCauseAnalysis` content, bearer tokens, or stack traces. Logging continues through SLF4J via `RequestLoggingFilter` and `ActorFilter`; `createdBy` is captured from `ActorHolder.get()` at construction.

REST routes live under `/api/v1/findings/**` and `/api/v1/findings/{id}/links/**`, authenticated via the shared `ApiPathMatrix` (bearer + browser-session chains).

## Consequences

### Positive

- Findings stay distinct from observations, controls, and the risk-management cluster - no semantic overload.
- Affected entities are project-scoped, validated references, not free text.
- Inbound `FINDING` link targets become consistent with the rest of the modeled graph; reverse lookup and graph traversal stop straddling modeled and unmodeled paths.
- Existing project scoping, auditing, validation, exception mapping, logging, and graph projection are reused - no parallel substrates.
- The next first-class GRC aggregates (evidence, audit records, remediation plans) extend `validateFindingTarget` and `FindingGraphProjectionContributor` instead of inventing new linking surfaces.

### Negative

- Three existing graph contributors (asset, control, risk) and the graph target resolver gain a `FindingRepository` dependency and one new switch case each.
- `MigrationSmokeTest` and `RequirementsE2EIntegrationTest` carry four new hardcoded version entries plus the corresponding audit-table assertions.

### Risks

- If `FINDING` is left external on any of the three inbound link substrates, graph traversal and reverse lookup will silently drift back into the placeholder shape.
- If `Evidence`, `Audit`, or `RemediationPlan` later land without extending `validateFindingTarget` to internal targets, those link target types will stay external indefinitely and the inconsistency this ADR closes for `FINDING` will recur.
- If a future implementation conflates `Finding` with `Observation` by adding severity / lifecycle / owner to the observation entity, two parallel records of the same deficiency will accumulate.

## References

- GC-V001 - Finding Entity (Wave 4, FUNCTIONAL, MUST)
- `architecture/notes/finding-entity-preflight.md` - Codex preflight architecture note
- ADR-024 - Threat Model Entry Boundary (parallel aggregate-boundary template)
- ADR-020 - Asset Cross-Entity Linking (origin of the `FINDING` placeholder target type)
- ADR-012 - Formal Methods Process (state-property L2 classification for transition logic)
- ADR-033 - Authenticated Audit Actor Provenance (Envers actor capture via `ActorHolder`)
