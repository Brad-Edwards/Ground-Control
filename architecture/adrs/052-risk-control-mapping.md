# ADR-052 — Risk-Control Mapping Aggregate (GC-T003)

**Status:** Accepted
**Date:** 2026-05-21
**Issue:** #258

---

## Context

GC-T003 requires a bidirectional many-to-many relationship between controls
(catalog `Control` or a new `ScopedControlImplementation` scoping overlay)
and risk items (`RiskScenario` or `RiskRegisterRecord`). Each link must carry
structured metadata: control role, mapping objective and scope (C3), optional
asset-boundary context (C2), optional methodology-profile influence payload
(C4), anchored observations and evidence provenance (C8), and must support
coverage-gap queries (C5a, C5b, C6) and a read-time effectiveness-feed per
risk assessment result (C7/C8).

The GC-H003 threat-traceability work (ADR-050 era) introduced
`ControlLink`/`RiskScenarioLink` projections but deliberately stopped short of
a canonical mapping aggregate. GC-T003 fills that gap.

---

## Decision

### 1. Canonical mapping owner

`RiskControlMapping` is the **sole canonical owner** of every control ↔ risk
relationship. No derived table or projection is needed. C5/C6 coverage queries
read `RiskControlMapping` directly via JPQL.

Rationale: a separate "union view" approach would split the ownership surface and
complicate referential integrity. The canonical-owner approach matches the existing
`AuditLink`, `ControlLink`, `ThreatModelLink` patterns while adding richer metadata
fields.

### 2. Polymorphic endpoints via paired nullable FKs

The control-side and risk-side each have two nullable FK columns (e.g.
`control_id` / `scoped_implementation_id`; `risk_scenario_id` /
`risk_register_record_id`). A DB `CHECK` constraint on each side enforces
exactly-one (XOR). Service-layer `validateExactlyOne*` guards reject violations
before hitting the DB.

This pattern mirrors the existing `ControlLink`/`RiskScenarioLink` polymorphism
and avoids a table-per-endpoint proliferation.

### 3. ScopedControlImplementation

A lightweight `@Audited` entity scoping a catalog `Control` to a specific
implementation context (e.g. "Email Gateway") with an optional `OperationalAsset`
boundary. Motivates the control-side polymorphism: callers that have decomposed
a catalog control into implementation units map at the SCI level rather than the
catalog level.

### 4. Methodology influence payload (C4)

`methodologyInfluence: Map<String,Object>` stored via `JacksonTextCollectionConverters.StringObjectMapConverter`.
When `methodologyProfileId` is provided, `MethodologyInfluenceValidator` checks
required fields and rejects unknown fields (when "properties" is declared in the
profile's `inputSchema`). Influence without a profile is allowed but not validated.

### 5. C6 transitive-through-record interpretation

A control is considered "mapped" if it maps directly to a scenario OR if it maps
to a register record that has at least one scenario. This is implemented via a
nested EXISTS subquery in `RiskControlMappingRepository.findUnmappedControlIds`.

### 6. C7 read-time aggregation

`RiskControlMappingFeedService.feedForAssessment` uses the existing
`ControlEffectivenessAssessmentRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc`
as-of query, picking the most recent assessment per control via `putIfAbsent`.
No materialized table; the feed is computed at read time.

### 7. C8 provenance edges

Two sub-tables anchor evidence to a mapping:
- `mapping_observation`: `@NotAudited @ManyToMany` join table to `Observation`.
  Separate from `RiskAssessmentResult`'s `risk_assessment_result_observation` table.
- `mapping_evidence`: `@ElementCollection` of `MappingEvidenceRef` embeddables
  (`evidenceRef`, `evidenceNote`, `evidenceArtifactId`).

### 8. Audit trail

`RiskControlMapping` and `ScopedControlImplementation` are both `@Audited`.
`@ManyToOne` refs to non-audited entities (`Project`, `OperationalAsset`,
`MethodologyProfile`) carry `@NotAudited` per the project audit convention.
Flyway migrations V119–V122 cover the parent tables and their `_audit` shadows.

### 9. MappingControlRole enum (ADR-034 mirror)

`MappingControlRole` is mirrored in:
- `mcp/ground-control/lib.js`: `MAPPING_CONTROL_ROLES`
- `frontend/src/types/api.ts`: `MappingControlRole` type + `MAPPING_CONTROL_ROLES` constant

Per the ADR-034 enum-contract policy, future changes to the Java enum must be
reflected in both mirrors in the same PR.

---

## Consequences

- Five new entity types in the domain graph (`RISK_CONTROL_MAPPING`,
  `SCOPED_CONTROL_IMPLEMENTATION`) with edges contributed by
  `RiskControlMappingGraphProjectionContributor`.
- Three new REST endpoint groups: `/scoped-control-implementations`,
  `/risk-control-mappings`, `/analysis/risk-control/**`.
- New `gc_risk_control_mapping` MCP tool.
- Flyway migrations V119–V122 must be listed in `MigrationSmokeTest` and
  `RequirementsE2EIntegrationTest`.
