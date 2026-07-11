# Contract Changes

Current contract version: 0.3.0

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
