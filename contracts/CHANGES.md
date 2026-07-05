# Contract Changes

Current contract version: 0.2.0

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
