# Contract Changes

Current contract version: 0.3.0

## 0.3.0 - 2026-07-11

BREAKING: Retirement of the composed GRC product surface and the
next-issue-recommendation close-path field (ADR-089, issue #1346).

- **BREAKING** - Removed REST operations for the composed GRC product
  surface: GRC analysis (`/api/v1/analysis/grc/**`), GRC assessment runs
  (`/api/v1/grc-assessment-runs/**`), derivations
  (`/api/v1/derivations/**`), architecture models
  (`/api/v1/architecture-models/**`), evidence campaigns
  (`/api/v1/evidence-campaigns/**`), the data classification lattice
  (`/api/v1/data-classification/**`), threat enumeration
  (`/api/v1/threat-enumeration/**`), control identification
  (`/api/v1/control-identification/**`), control packs
  (`/api/v1/control-packs/**`), risk appetite profiles
  (`/api/v1/risk-appetite-profiles/**`), methodology profiles
  (`/api/v1/methodology-profiles/**`), risk assessment results, risk
  register records, treatment plans (`/api/v1/treatment-plans/**`),
  control effectiveness assessments
  (`/api/v1/control-effectiveness-assessments/**`), and the composed
  Control/Assurance, Evidence/State, Threat-Modeling, and Risk-Scenario
  workspace read endpoints. Removed paths are absent from `ApiPathMatrix`
  and `contracts/authz/path-matrix.yaml`; requests now receive the
  standard `NoResourceFoundException` 404 `ErrorResponse`, never a
  GRC-specific error shape.
- **BREAKING** - Removed the matching MCP tools, `gc_analyze` GRC kinds,
  and `gc_query` allowlist entries for the surfaces above, and the
  `/assess` skill.
- **BREAKING** - `gc_close_issue_after_merge` no longer returns
  `next_issue_recommendation`, `next_issue_recommendation_reason`, or
  `next_issue_recommendation_error` in any form (including `null`). The
  close envelope now carries only linked-PR resolution, merge-state
  verification, and idempotent close result fields.
- Retained aggregates and their existing REST/MCP surfaces are
  unaffected: `Control`, `ControlTest`, `EvidenceArtifact`, `Finding`,
  `Asset`, `RiskScenario`, `ThreatModel`, risk-control mapping, and
  requirement traceability.
- The committed OpenAPI document and generated TypeScript client are
  regenerated to match; MCP Zod shapes, tool registration, client
  helpers, and tool-description tests were updated in lockstep so no
  unadvertised adapter remains callable.
- No deprecation window is offered: this is pre-alpha product software
  with no external consumer commitment, the milestone-25 validation
  found these surfaces added workflow cost and assurance risk without
  credible defect-specific risk reduction, and a deprecated-then-removed
  cycle would extend that cost rather than end it. See ADR-089.

## 0.2.0 - 2026-07-05

Deterministic core `/implement` workflow payload contracts (GC-O009 phase 2,
issue #1277, ADR-081/ADR-082).

- Added `contracts/schemas/workflow/` payload schemas for the deterministic
  `/implement` Temporal workflow and its activities: `implement-workflow.v1`,
  `implement-signals.v1`, `resolve-issue.v1`, `completion-gate.v1`,
  `quality-gate.v1`, `git-publish.v1`, `open-pull-request.v1`,
  `ci-observation.v1`, `sonar-gate.v1`, `merge-observation.v1`,
  `status-transition.v1`, `traceability-reconcile.v1`, `close-issue.v1`, and
  the content-activity seam contracts (`content-activities.v1`).

These are additive new schemas (new `gc.workflow.*.v1` ids); no existing
contract field was removed or retyped, so no BREAKING change is declared.

## 0.1.0 - 2026-07-04

Initial publication of the Ground Control contract surface.

- Added the committed OpenAPI document of record under `contracts/openapi/`.
- Added generated TypeScript API types under `contracts/gen/typescript/`.
- Added durable-record and workflow-record JSON Schema homes under `contracts/schemas/`.
- Added the authorization path matrix contract under `contracts/authz/`.

No BREAKING contract changes are declared in this initial publication.
