# ADR-045: Evidence Derivation and Temporal State History

## Status

Accepted

## Date

2026-05-17

## Context

GC-M016 requires the system to support deriving durable evidence and historical assurance conclusions from observations, tests, and attestations without overwriting prior state, and to let consumers distinguish current observed state, historical observations, and summarized evidence artifacts. The codex architecture preflight (`architecture/notes/evidence-derivation-temporal-state-preflight.md`) ruled that three concepts must stay separable in the data model:

- **Current observed state** is a read projection over history (the "latest non-expired observation" view), not a row that gets updated in place.
- **Historical observations** are durable point-in-time facts. The observation's value, source, observed-at time, and evidence reference must not be overwritten to represent a new fact.
- **Summarized evidence and assurance conclusions** are their own derived artifacts. A summary carries its own derivation time, inputs, method, confidence/assurance, and consumer-facing identity; it must not erase or replace the source facts.

The codebase already owns the input layers:

- `Observation` (GC-M015) is a `@Audited`, time-stamped state fact about an asset, with `findLatestByAssetId(now)` as the current-state projection and `findByAssetId` as the full ordered history.
- `ControlTest` (GC-I012 / ADR-039) is the per-execution control evidence artifact.
- `ControlEffectivenessAssessment` (GC-I013 / ADR-039) is the per-control assurance conclusion—multiple rows over time form the historical assurance series, ordered by `assessedAt DESC`.
- `VerificationResult` (ADR-014) is the prover/tool evidence record for a requirement target.
- `RiskAssessmentResult` is the methodology-scoped risk conclusion.
- `Finding` (GC-V001 / ADR-038) is the governed deficiency record.

What is missing is the summarization aggregate itself: a durable, project-scoped, append-only record that explicitly cites which observations, tests, assessments, verification results, risk assessments, findings, or external attestations it derives from, with its own derivation timestamp, method, and confidence/assurance.

ADR-038 declared `EVIDENCE` an external placeholder target across `FindingLinkTargetType`, `AssetLinkTargetType`, `ControlLinkTargetType`, `RiskScenarioLinkTargetType`, and `ThreatModelLinkTargetType`. Promoting those placeholders to internal targets is a separate substrate-consistency concern (one-place edit per substrate) and is not part of GC-M016's derivation semantics.

## Decision

### 1. EvidenceArtifact is a separate aggregate from observations, tests, assessments, verification results, risk-assessment results, and findings

Introduce a dedicated aggregate at `domain/evidence/`:

```
domain/evidence/
    model/
        EvidenceArtifact.java
        EvidenceSourceRef.java          (record persisted inside the sources JSON column)
    state/
        EvidenceType.java               (enum)
        EvidenceSourceKind.java         (enum)
    repository/
        EvidenceArtifactRepository.java
    service/
        EvidenceArtifactService.java
        CreateEvidenceArtifactCommand.java
api/evidence/
    EvidenceArtifactController.java
    EvidenceArtifactRequest.java
    EvidenceArtifactResponse.java
    EvidenceSourceRefDto.java
domain/graph/service/
    EvidenceArtifactGraphProjectionContributor.java
```

`EvidenceArtifact` owns the summarization itself: `uid`, `title`, `summary`, `evidenceType`, `derivationMethod`, `derivedAt`, `derivedBy`, `assuranceLevel`, `confidence`, `notes`, `supersededByArtifactId`, and a `sources` list. Sources are represented as `EvidenceSourceRef` records, not as embedded copies of the source row data.

The entity must not be implemented by extending the semantics of `Observation`, `ControlTest`, `ControlEffectivenessAssessment`, `VerificationResult`, `RiskAssessmentResult`, or `Finding`.

### 2. Reuse the established domain and persistence shape

`EvidenceArtifact` follows the same shape used by the existing audited aggregates:

- Extends `BaseEntity` (UUID id, `createdAt` / `updatedAt`).
- Project-scoped through `Project` and marked `@NotAudited` on the project reference.
- `@Audited` on the entity. Hibernate Envers carries the `GroundControlRevisionEntity` actor / reason metadata (ADR-033). `derivedBy` is populated server-side from `ActorHolder.get()` at create time; clients cannot supply it.
- Flyway migrations ship the `evidence_artifact` table and the matching `evidence_artifact_audit` shadow with BaseEntity timestamps. `AuditRetentionJob.AUDIT_TABLES` lists the audit shadow so retention purges it together with the rest.
- `MigrationSmokeTest` and `RequirementsE2EIntegrationTest` carry table-existence + column probes and the new version numbers.

The `sources` list is persisted as JSON in a single TEXT column via a new `EvidenceSourceRefListConverter` added to `JacksonTextCollectionConverters`. This matches the existing pattern used by `ControlEffectivenessAssessment.supportingTestIds`, `VerificationResult.evidence`, and the pack-registry payload converters; it avoids a per-kind join table for what is logically a many-to-many derivation seam.

### 3. Source references are dual-mode (entity-id internal, identifier external)

`EvidenceSourceRef` carries `sourceKind`, `sourceEntityId`, `sourceIdentifier`, and `role`. The service validates per kind:

- Internal kinds (`OBSERVATION`, `CONTROL_TEST`, `CONTROL_EFFECTIVENESS_ASSESSMENT`, `VERIFICATION_RESULT`, `RISK_ASSESSMENT_RESULT`, `FINDING`) require `sourceEntityId` to resolve to a project-scoped row in the corresponding repository. `sourceIdentifier` must be null. Cross-project references fail closed with `NotFoundException` (code `evidence_source_target_not_found`).
- External kinds (`ATTESTATION`, `EXTERNAL`) require a non-blank `sourceIdentifier`. `sourceEntityId` must be null.

`role` is free text (for example, `"primary"`, `"supporting"`) preserved on the artifact for provenance. It is not validated semantically.

Mirroring the `FindingLink` target convention keeps the source-reference seam parameterized by data rather than by controller branches: adding a new internal source kind requires extending `EvidenceSourceKind`, mapping it in the service's existence-check switch, and updating the graph projection—no new entity, no new resolver, no new controller route.

### 4. Append-only enforcement

The controller exposes no PUT and no DELETE. The only post-create mutation is `POST /api/v1/evidence-artifacts/{id}/supersede`, which:

1. Loads the prior artifact (project-scoped); refuses with `ConflictException` (code `evidence_artifact_already_superseded`) if `supersededByArtifactId` is already set.
2. Creates a NEW artifact through the same validation path as plain create.
3. Writes the prior artifact's `supersededByArtifactId` to the new artifact's UUID exactly once.

Envers records every revision (including the single supersede write), so the audit history of any one artifact tells the full story without the canonical row being mutated to represent a new fact. There is no in-place edit path and no soft-delete; consumers that want a corrected summary publish a new artifact and supersede.

The append-only contract is the API-boundary half of clause C2 of GC-M016 ("without overwriting prior state"); the service-layer state check on supersede is the second half. The database carries no shape-level guard—the service is the single enforcement point.

### 5. Graph integration via the existing mixed-graph projection

`GraphEntityType` gains `EVIDENCE_ARTIFACT`. `EvidenceArtifactGraphProjectionContributor` registers via the existing Spring `GraphProjectionContributor` discovery and projects:

- One node per artifact (`EVIDENCE_ARTIFACT` type, properties carry `evidenceType`, `derivationMethod`, `derivedAt`, optional `derivedBy` / `assuranceLevel` / `confidence` / `supersededByArtifactId`).
- One `HAS_SOURCE` edge per internal-kind source, pointing to the existing graph node for `OBSERVATION`, `CONTROL_TEST`, `CONTROL_EFFECTIVENESS_ASSESSMENT`, `VERIFICATION_RESULT`, `RISK_ASSESSMENT_RESULT`, or `FINDING`. External-kind sources (ATTESTATION, EXTERNAL) carry only a string identifier and do not produce graph edges.
- One `SUPERSEDED_BY` edge from a superseded artifact to its replacement when `supersededByArtifactId` is set.

No bespoke evidence-graph endpoint, no direct AGE writes from the controller or service. JPA remains the source of truth; the graph is the read-side projection that makes cross-source queries ("which artifacts cite Observation X?") traversable.

This ADR does not promote the `EVIDENCE` constant in `FindingLinkTargetType`, `AssetLinkTargetType`, `ControlLinkTargetType`, `RiskScenarioLinkTargetType`, or `ThreatModelLinkTargetType` from external to internal. Those substrates encode a separate inbound-link consistency rule (ADR-038) whose graduation is a one-place edit per substrate against `validateFindingTarget` / `validateAssetTarget` / etc. plus the corresponding `*GraphProjectionContributor`—independent of GC-M016's derivation semantics.

### 6. Three-layer read contract

The three layers GC-M016 requires consumers to distinguish are addressed by three explicit endpoints:

| Layer | Endpoint | Source |
|---|---|---|
| Current observed state | `GET /api/v1/assets/{assetId}/observations/latest` | `ObservationController.listLatest` (existing) |
| Historical observations | `GET /api/v1/assets/{assetId}/observations` | `ObservationController.list` (existing, full history ordered DESC) |
| Summarized evidence artifacts | `GET /api/v1/evidence-artifacts` | `EvidenceArtifactController.list` (new) |

Historical assurance conclusions for a control are the existing `GET /api/v1/control-effectiveness-assessments?controlId=X` ordered by `assessedAt DESC`. The new `/api/v1/evidence-artifacts` list endpoint additionally filters by `evidenceType` and excludes superseded artifacts by default (opt in with `includeSuperseded=true`).

### 7. Validation, exception handling, observability, and authorization stay shared

DTO `@Valid` enforces shape (`@NotBlank`, `@NotNull`, `@Size`, `@NotEmpty`, Jackson enum parsing). Service validation owns: project scoping, uid uniqueness, source existence + same-project, source dual-mode shape (internal entity-id xor external identifier), supersede single-shot state check. Error envelopes use `NotFoundException`, `ConflictException`, and `DomainValidationException` through `GlobalExceptionHandler` and the shared `ErrorResponse`. Detail names fields and stable codes (`evidence_artifact_uid_conflict`, `evidence_artifact_already_superseded`, `evidence_source_target_not_found`); it never echoes `summary`, `notes`, or `sourceIdentifier` content.

Authorization is automatic via `ApiPathMatrix` (`/api/v1/**` → authenticated). No admin-only path. Logging uses SLF4J with stable low-cardinality event names (`evidence_artifact_created`, `evidence_artifact_superseded`); it never logs raw summary or source content.

The MCP `gc_query` allowlist (`mcp/ground-control/gc-query.js`) gains `/api/v1/evidence-artifacts` so agents can read the new layer. A new curated `gc_evidence` MCP tool ships with this ADR exposing the two append-only write actions (`create`, `supersede`) per the repo's controller-parity rule (`run_controller_contracts` in `tools/policy/checks.py`); the tool intentionally has no `update` or `delete` action so it cannot violate the append-only contract that the controller enforces at the HTTP boundary. Reads (list, get) route through `gc_query` rather than duplicating the read surface in the curated tool.

## Consequences

### Positive

- The three GC-M016 layers (current / historical / summary) are addressable at separate API endpoints that consumers can wire to without ambiguity.
- Summarized evidence is durable, audited, and never silently overwritten—the supersede chain makes prior conclusions traceable.
- The source-reference dual-mode keeps the derivation seam parameterized by data: extending to new internal source kinds is a one-place edit in the service's existence-check switch plus the graph projection.
- Existing project scoping, auditing, validation, exception mapping, logging, authorization, and graph projection are reused. No parallel substrates.
- The graph projection makes "which artifacts cite source X" traversable through the existing mixed-graph endpoint, not a bespoke evidence query path.

### Negative

- `MigrationSmokeTest` and `RequirementsE2EIntegrationTest` carry two more hardcoded version entries plus the corresponding audit-table assertions.
- `JacksonTextCollectionConverters` grows one more converter; the JSON-stored `sources` list is not SQL-queryable on its own—the graph projection is the cross-entity query path.
- `RiskAssessmentResultRepository` and `ObservationRepository` gain a small `existsByIdAndProjectId` query so the service can validate sources without fetching unrelated `JOIN FETCH` data.

### Risks

- If a future implementation conflates `EvidenceArtifact` with `Observation` (by adding summary/derivation fields back onto observations), two parallel records of the same summarization will accumulate. The aggregate boundary above is the safeguard.
- If a future code path bypasses `EvidenceArtifactService` and writes to the repository directly, the supersede-once invariant can be violated. Service-layer ownership of the write path is the safeguard; the controller-architecture invariants enforced by ArchUnit keep controllers from short-circuiting the service.
- If consumers treat `current observed state`, `historical observations`, and `summarized evidence artifacts` as interchangeable views of the same data, the temporal traceability this ADR exists to protect collapses. The endpoint table above is the contract.

### 8. Executable evidence kinds and append-only expiration (GC-I003 / GC-I004 amendment)

GC-I003 calls for evidence that is executable and verifiable—test results, security-scan outputs, formal-proof artifacts, CI/CD pipeline results. ADR-014 already covers prover-output evidence as a first-class `VerificationResult`, and ADR-039 / Decision §1 already declares `ControlTest` as the per-execution control-evidence record. Those are the executable-evidence substrates for *internal* test/proof artifacts; no new abstraction is required for them. What GC-I003 adds is two *external* source kinds for evidence that lives outside the system and is referenced only by an opaque, length-capped identifier:

- `EvidenceSourceKind.CI_PIPELINE_RESULT`: a reference to a CI run (for example, a GitHub Actions run URL or run-key).
- `EvidenceSourceKind.SECURITY_SCAN_RESULT`: a reference to a scanner finding (for example, a SonarCloud, Snyk, or Trivy report key).

Both kinds follow the existing external-mode shape declared in §3: `sourceIdentifier` is required, `sourceEntityId` must be null, and the server never dereferences the identifier (SSRF guard—CI/CD result URLs are stored but never fetched server-side). The existence-check switch in `EvidenceArtifactService` and the graph-projection switch in `EvidenceArtifactGraphProjectionContributor` route both kinds as external (no graph edge), matching `ATTESTATION` / `EXTERNAL`.

GC-I004 (continuous compliance monitoring) adds two optional columns to `EvidenceArtifact` (`expiresAt` (timestamp) and `validityWindowDays` (positive integer)) without violating the append-only contract declared in §4:

- Setting `expiresAt` at create time does NOT mutate the row after that instant. Expiration is derived current-state, not an in-place edit—`EvidenceArtifact.isExpiredAt(asOf)` is the projection; the row is never overwritten by elapse.
- `validityWindowDays` is informational metadata. The sweep job decides expiry purely from `expiresAt`; the column lets callers express "valid 90 days" without recomputing the derived instant.
- A superseding artifact gets its own `expiresAt` / `validityWindowDays`. The prior row's values are preserved exactly—the supersede-once invariant declared in §4 covers them by the same mechanism.

A new scheduled `EvidenceExpirySweepJob` (Spring `@Scheduled`, lives in `infrastructure/compliance/` next to `AuditRetentionJob`, opt-in via `groundcontrol.compliance.evidence-expiry-enabled=true`) sweeps for `expiresAt <= now` and publishes one `EvidenceExpiryEvent` per artifact. A new `ComplianceDriftDetectorService` (lives in `domain/compliance/`) listens synchronously to that event and to the existing `ControlStateChangedEvent` (reused from the GC-T004 / C8 reassessment substrate), writing one `ComplianceDriftEvent` row per signal. The detector mirrors `ReassessmentSignalService`'s synchronous contract—every listener is `@EventListener`, never `@TransactionalEventListener`, so a listener failure rolls back the publishing mutation and the signal cannot silently disappear.

Drift events are append-only. The only post-create mutation is a one-shot `acknowledgedAt` write (mirrors the supersede-once pattern from §4). Resolutions are published as new rows of category `RESOLUTION`, not as updates to a prior row. The detector liveness probe (`GET /api/v1/compliance-drift-events/liveness`) returns `sampledAt`, `lastDetectedAt`, `lastSweepAt`, and the unacknowledged-event count so a stalled monitor is directly observable—without it, a dead scheduler would silently look "compliant" because no new events get published (security note in the cluster scope).

Drift events that surface to GitHub issues sanitize artifact content per ADR-029 at the issue boundary; the drift row's `summary` field is short, length-capped (1000 chars), and never echoes evidence content or field values. The detector summary for control-state events joins only the changed-field NAMES—never the old/new values, so a sensitive value in a changed field cannot leak into the drift row.

Posture is NOT a new aggregate. A read projection over `compliance_drift_event` + the existing `ComplianceFrameworkMapping` aggregate (cluster 4 promotion of `AuditLinkTargetType.FRAMEWORK`) is the posture surface; this ADR ships the signal stream and the synchronous detector only. The MCP surface for drift events is read-only via `gc_query`'s allowlist (extended to include `/api/v1/compliance-drift-events`); there is intentionally no new top-level MCP tool so an agent cannot publish a drift event by hand and contaminate the durable signal stream.

## References

- GC-M016—Evidence Derivation and Temporal State History (Wave 4, FUNCTIONAL, MUST)
- GC-I003—Executable Evidence Collection (Wave 4, FUNCTIONAL, SHOULD)
- GC-I004—Continuous Compliance Monitoring (Wave 5, FUNCTIONAL, COULD)
- `architecture/notes/evidence-derivation-temporal-state-preflight.md`—Codex preflight architecture note
- `architecture/notes/continuous-compliance-monitoring-preflight.md`—GC-I003/I004 architecture note
- GC-M013—Asset Topology and Boundary Relationships (Wave 4)
- GC-M014—External Identifiers and Source Provenance (Wave 4)
- GC-M015—Observation and State Fact Entity (Wave 4)
- GC-M017—Asset-Centric Traceability and Impact Context (Wave 4)
- ADR-014—Pluggable Verification Architecture
- ADR-026—REST API Security Chain
- ADR-029—Issue Surface Sanitization
- ADR-033—Authenticated Audit Actor Provenance
- ADR-034—Single-Source Enum Policy
- ADR-035—MCP Curation
- ADR-038—Finding Entity Boundary (defines the inbound EVIDENCE placeholder substrates)
- ADR-039—Control Verification Subsystem (defines ControlTest and ControlEffectivenessAssessment as evidence-and-conclusion seams)
