# Contract Changes

Current contract version: 0.6.0

## 0.6.0 - 2026-07-15

Traceability context-graph projection (issue #1308, ADR-084).

- **BREAKING**: removed the never-emitted `CONTROL_LINK` and `AUDIT_LINK`
  values from `GraphEntityType`; links remain graph edges. The previously
  retired `RISK_APPETITE_PROFILE` value remains absent.
- Added `ARTIFACT_REFERENCE` for project-qualified, identifier-addressed
  traceability endpoints and projected all five requirement traceability edge
  kinds into the mixed graph.
- Graph response entity fields now publish the closed `GraphEntityType` enum in
  OpenAPI. Generated TypeScript exports `GRAPH_ENTITY_TYPES` for iterable UI
  coverage while preserving the existing JSON string values.

## 0.5.0 - 2026-07-14

Context-graph ontology authority (GC-O014, issue #1307, ADR-084).

- Added the versioned concept-family, controlled-vocabulary, and
  artifact-binding contracts under `contracts/ontology/`.
- Added a bidirectional policy gate between those bindings and the live Java
  vocabulary inventory: `GraphEntityType`, graph link/relation enums,
  `ProvenanceEdgeRelation`, every `GraphProjectionContributor`, and literal
  contributor edge terms. This publication does not rename emitted graph
  values or change runtime graph behavior.
- This initial publication is additive. A later breaking ontology change must
  use a versioned filename and carry a declaration here; the current OpenAPI
  breaking-change script does not compare ontology semantics.

## 0.4.0 - 2026-07-12

Temporal orchestration lane removal (issue #1359; ADR-028, ADR-081, ADR-088
all superseded).

- **BREAKING**: the entire `contracts/schemas/workflow/` activity/workflow
  payload contract surface is removed: `content-activities.v2` (which
  retired `v1` in 0.3.0 below and never shipped past this repo),
  `implement-workflow.v1`, `implement-signals.v1`, `resolve-issue.v1`,
  `completion-gate.v1`, `quality-gate.v1`, `git-publish.v1`,
  `open-pull-request.v1`, `ci-observation.v1`, `sonar-gate.v1`,
  `merge-observation.v1`, `status-transition.v1`,
  `traceability-reconcile.v1`, `close-issue.v1`, and the directory's
  companion `README.md`. These schemas governed the Temporal `/implement`
  engine's activity I/O; the engine never registered a production activity
  implementation and is withdrawn in full (see the ADR-028 amendment for the
  rationale). `contracts/schemas/workflow/workflow-run-record.v1.schema.json`
  is unaffected - it is the ADR-061 telemetry surface, not an activity
  payload, and was never part of this contract family's removal.
- The generated OpenAPI document and TypeScript client no longer carry the
  `/api/v1/workflow-executions` paths or their request/response schemas
  (`StartWorkflowExecutionRequest`, `SendSignalRequest`,
  `WorkflowExecutionResponse`, `WorkflowExecutionStartResponse`). Regenerated
  via `make contracts`.
- `contracts/authz/path-matrix.yaml` drops the `/api/v1/workflow-executions**`
  rows.

The 0.3.0 entry below is retained as a historical record; the
`content-activities.v2` schema and `ResolvedLlmRoute` record it describes no
longer exist in this repo.

## 0.3.0 - 2026-07-11

LLM activities and provider boundary (GC-O009 phase 5, issue #1280, ADR-028).

- **BREAKING**: `content-activities.v1` is retired and replaced by
  `contracts/schemas/workflow/content-activities.v2.schema.json`
  (`gc.workflow.content-activities.v2`). `AuthorPlanInput` gains two required
  fields, `project` and `route` (the new `ResolvedLlmRoute` record—a closed
  safe scalar set: contract version, project, stage, tier, canonical provider
  id, canonical model id, config digest). This lets `authorPlan` resolve a
  trusted, project-scoped LLM route instead of inferring project ownership
  from the issue number, a local checkout, or the workflow id (preflight
  requirement). Every other record in the schema (`AuthorPlanResult`,
  `ImplementChangeInput/Result`, `CodexReviewInput/Result`,
  `TestQualityReviewInput/Result`, `ReadinessRecordInput/Result`,
  `FinalReportInput/Result`) is unchanged; `AuthorPlanResult(posted,
  commentId)` in particular carries no route/model data. Migration: any future
  caller building `AuthorPlanInput` must supply `project` and a resolved
  `route`; there is no default.
- Added `contracts/schemas/workflow/implement-workflow.v1.schema.json`
  `ImplementWorkflowInput.route`—the same closed `ResolvedLlmRoute` scalar
  set, optional and nullable. This is an additive, non-breaking evolution of
  `implement-workflow.v1`: the field is not in `required`, so existing
  producers/consumers are unaffected. `WorkflowExecutionService` resolves the
  route and binds it to the execution before `WorkflowControlPort.start`, so
  a normal start never creates a workflow already known to be unrunnable.

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
