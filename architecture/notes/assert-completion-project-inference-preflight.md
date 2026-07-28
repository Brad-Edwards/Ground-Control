# Completion Project Inference Preflight

Issue: #1462
Requirement: none

This note records architecture guardrails for project inference and structured
lookup failures in the `/implement` completion assertion. It is guidance only,
not an implementation plan.

## Binding Contract

- Keep one precedence rule: a non-blank explicit `project` wins; otherwise use
  the project returned by `getRepoGroundControlContext(repo_path)` when that
  context is valid; otherwise leave the REST request unqualified.
- An unresolved repo context is not a locally synthesized backend failure. The
  existing backend lookup remains the authority: a single-project deployment
  may resolve the sole project, while a multi-project deployment returns
  `project_required` with `detail.project_count`.
- Do not parse `.ground-control.yaml` a second way or extract only its `project`
  key. `getRepoGroundControlContext` and its strict parser remain the sole
  configuration boundary under ADR-027.
- Resolve the project only on paths that perform a project-scoped lookup. An
  authorized traceability override skips that lookup and must retain its
  existing validation and posting behavior without acquiring a config
  precondition. The pre-merge readiness path likewise performs no traceability
  lookup.
- Preserve the backend `RequestError` cause across every composition boundary:
  `runAssertTraceabilityReconciled`, `runAssertCompletion`, and the
  `gc_implement_mechanical` `finalize` result must keep the stable
  `project_required` code and original detail map machine-readable. Do not
  interpolate either into a replacement message-only error.
- All config and REST reads must finish before the traceability phase marker or
  final report is posted. A resolution or lookup failure must therefore remain
  side-effect free and safe to retry.

## Canonical Incumbents

- Repository context: `getRepoGroundControlContext`, `parseGroundControlYaml`,
  `GROUND_CONTROL_PROJECT_RE`, and `ensureGitRepo`.
- MCP input boundaries: the existing Zod shapes in
  `mcp/ground-control/index.js` and
  `mcp/ground-control/gc-implement-mechanical.js`. `project` remains optional;
  do not introduce a second project schema.
- REST transport: `buildUrl`, `request`, `parseErrorBody`, `RequestError`, and
  `addAuthorizationHeader`. Project scope travels through `URLSearchParams`;
  credentials stay in the Authorization header and never enter argv.
- Backend authority: `RequirementController.findTraceabilityByArtifact`,
  `ProjectService.resolveProjectId`, `TraceabilityService.findByArtifact`, and
  the project-scoped `TraceabilityLinkRepository` query. The backend already
  rejects unscoped multi-project lookup through
  `DomainValidationException` → `GlobalExceptionHandler` → `ErrorResponse`.
- Workflow composition: `runAssertTraceabilityReconciled`,
  `runAssertCompletion`, and `gc_implement_mechanical` `finalize`. Keep the
  assertion as the owner of project resolution so direct and composed callers
  cannot drift.
- Durable side effects: `postPhaseMarker` and `runPostFinalReport` remain the
  only completion-record writers. Their existing sensitive-content,
  reserved-marker, body-size, merge, and prerequisite gates are unchanged.

## Cross-Cutting Guardrails

- Authentication and authorization remain on the existing `/api/v1/**` path:
  MCP bearer-token header routing, `IpAllowlistFilter`,
  `BearerTokenAuthFilter`, `ApiPathMatrix`, and `ActorFilter`. This issue adds
  no role, token, CIDR, principal, or environment-binding surface.
- Config errors may identify the config status or path when needed, but must
  not return config contents, suggested YAML, environment values, credentials,
  stack traces, or arbitrary filesystem data.
- Preserve the existing lookup-specific errors for non-`project_required`
  failures. Do not turn requirement lookup, traceability-link lookup, and issue
  reverse-lookup failures into one ambiguous code.
- Do not add backend controllers, DTOs, services, repositories, migrations, or
  persistence. The backend project-scoping and error envelope are already
  correct.
- Do not add logs for the normal inference path. Existing request and workflow
  telemetry remain sufficient; if a failure is observed, use the bounded error
  code rather than project names or raw response bodies as the signal.

## Contract Coverage

- Cover direct inference, explicit override precedence, and the fallback that
  lets the backend emit `project_required` with `project_count`.
- Cover the reported composite path through `runAssertCompletion`, including
  the empty-requirements orphan audit, the unchanged assertion entry, no final
  report, and no marker post on failure.
- Cover the current Step 17 entry through `gc_implement_mechanical` so
  `completion.project` may be omitted and structured failure detail remains
  available to the caller.
- Preserve existing override and pre-merge tests; they guard against making
  project resolution an unrelated prerequisite.
- Keep the MCP descriptions, `skills/implement/SKILL.md`, and
  `skills/implement/steps/step-17-completion.md` synchronized. The detailed
  step contract should state that `project` is optional, inferred from repo
  context when available, and explicitly overridable.

## Extensibility And Non-Goals

The extension seam is the existing pair `(repo_path, project?)`: another
repo-scoped workflow tool can later apply the same precedence without changing
the configuration schema. Do not introduce a generic resolver registry or a
second error hierarchy until multiple independent callers justify it.

This issue does not redesign backend project resolution, make project optional
for project-scoped data access, add cross-project lookup, qualify GitHub issue
artifact identifiers, change traceability reconciliation semantics, alter the
Phase D/Phase E split, merge or close a PR, or change the `/quickfix` lane.
