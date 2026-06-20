# Manual Test Execution Runner Preflight

Issue: #676
Requirement: TC-009

This note records architecture guardrails for the upcoming browser-based
manual test execution runner. It is not an implementation plan.

## Boundary

The runner is a workflow surface over the existing `TestRun` aggregate
(ADR-049). It must not introduce a parallel "execution session" concept unless
a later requirement needs collaboration, locking, or offline sync. The canonical
execution record remains:

- `TestRun` for one frozen suite pass against a plan.
- `TestRunCaseResult` for each snapshotted test case in that run.
- A new normalized run-side step-result child, if per-step execution evidence
  is persisted.

Keep the authored/executed split strict. `TestCaseStep` owns reusable authored
action and expected-result content. Runtime step status, comments, timestamps,
and observed notes belong on run-side child rows. Do not write TC-009 evidence
into `TestCaseStep.actualResult`; ADR-041 keeps that field definition-time.

## Incumbents To Reuse

- **Domain boundary:** stay inside `domain/testcases/{model,state,repository,
  service}` and `api/testcases`, building on `TestRunService`,
  `TestRunCaseResultRepository`, `TestCaseStepRepository`, and the existing
  test-management state enums.
- **Run membership:** use `TestRunCaseResult` as the case execution anchor. A
  step result must hang off the run case result or carry both `test_run_id` and
  `test_run_case_result_id`; it must never point only at a live
  `TestCaseStep`, because the run's historical membership is frozen.
- **Authored-step snapshot:** preserve enough step metadata at run time
  (`test_case_step_id`, step number, action, expected result, snapshot order)
  that later authored-step edits or renumbering do not rewrite execution
  evidence. The existing `TestRunCaseResult` UID/title snapshot is the model.
- **Status vocabulary:** reuse `TestRunCaseResultStatus` for both per-case and
  per-step outcome values (`NOT_RUN`, `PASSED`, `FAILED`, `BLOCKED`,
  `SKIPPED`) unless implementation proves a step needs a materially different
  domain word. Do not create duplicate pass/fail/blocked/skip enums with the
  same meaning.
- **Pause/resume:** model pause/resume through `TestRunStatus` and timestamps.
  The existing lifecycle already has `PLANNED`, `IN_PROGRESS`, `COMPLETED`,
  `ABORTED`, `ARCHIVED`; "pause" should be represented as retained progress
  without inventing a scheduler or job state machine. If a visible paused state
  is required, extend `TestRunStatus` deliberately and mirror it via ADR-034
  rather than adding an unrelated boolean.
- **Validation and errors:** keep Bean Validation on request records, service
  validation for project ownership and run/case/step membership, and the
  existing `DomainValidationException`, `ConflictException`, and
  `NotFoundException` hierarchy so `GlobalExceptionHandler` emits the shared
  `ErrorResponse`.
- **Frontend:** use `frontend/src/lib/api-client.ts` for all browser writes so
  ADR-037 CSRF handling and 401 redirect behavior remain centralized. Mirror
  DTOs/enums in `frontend/src/types/api.ts` and follow existing route/layout
  patterns in `frontend/src/routes.tsx` and `AppLayout`.
- **MCP and docs parity:** any backend controller additions must update
  `docs/API.md`, `mcp/ground-control/lib.js`, `mcp/ground-control/index.js`,
  MCP adapter tests, and frontend type mirrors. `tools/policy/checks.py`
  enforces controller parity.

## Cross-Cutting Layers

- **Authentication / authorization:** runner endpoints should live under
  `/api/v1/test-runs/**` so both bearer and browser-session chains pass through
  `IpAllowlistFilter`, `BearerTokenAuthFilter` or form-session auth,
  `ApiPathMatrix`, and `ActorFilter`. No endpoint-local bearer parsing, actor
  override fields, or runner-specific role table.
- **Browser CSRF and session handling:** mutating runner UI calls must use the
  shared API client, which reads the `XSRF-TOKEN` cookie and sends
  `X-XSRF-TOKEN`; 401 responses must keep using the central login redirect path.
- **Request shape checks:** Jackson owns UUID, `Instant`, and enum parsing.
  Bean Validation owns required status fields, comments/notes length bounds,
  and list/bulk caps if a batch update endpoint is added. Services own
  "case is part of run," "step belongs to the snapshotted case," "step result
  belongs to this run", and "terminal/archived runs cannot be mutated"
  semantics.
- **Project scope:** every run, case result, step result, authored step lookup,
  and test case lookup must be scoped through the resolved project. Foreign
  project IDs should surface as 404 concealment, matching existing
  `TestRunService` patterns.
- **Persistence / audit:** use Flyway migrations, `BaseEntity`, normalized
  rows, Envers audit shadows, and `AuditRetentionJob.AUDIT_TABLES`. Avoid JSON
  blobs for step outcomes or comments; these are user evidence and need
  queryability, validation, retention, and audit history.
- **Error envelopes:** parser, validation, conflict, and not-found failures
  must route through `GlobalExceptionHandler` / `ErrorResponse`. Error details
  may name fields and valid statuses but must not echo comments, notes,
  authored step bodies, cookies, authorization headers, or stack traces.
- **Logging / observability:** use SLF4J lifecycle logs with low-cardinality
  fields (`run_id`, `case_result_id`, `step_result_id`, status, counts). Do not
  log raw step comments, notes, action text, expected results, request bodies,
  tester names, cookies, or tokens.
- **Config / env / OS exposure:** TC-009 needs no new secrets, subprocesses,
  shell-outs, temp files, filesystem paths, external network clients, or
  process argv content. If autosave intervals, batch sizes, or stale-lock
  windows become configurable, bind them through validated
  `@ConfigurationProperties`; do not read ad hoc environment variables.
- **Workflow policy:** `.ground-control.yaml`, `.gc/plan-rules.md`,
  `bin/policy`, ADR drift checks, controller-contract checks, migration smoke
  tests, frontend tests, MCP tests, enum-contract tests, and changelog
  fragments are in scope for implementation completion. Repo work must finish
  with `make policy`.

## Extensibility

The load-bearing seam is the run-side step-result snapshot.

Per-step execution should be parameterized by stable identifiers and snapshot
metadata, not by the current authored step list. This keeps future edits cheap:
bulk result updates, autosave, attachments/evidence, defect linkage,
multi-tester handoff, offline resume, and automation adapters can extend
run-side result rows without changing authored `TestCaseStep` or re-resolving
`TestSuite`.

If the UI needs resumability beyond ordinary persisted state, store an explicit
cursor such as "current case result id / current step result id" on the run or a
small run-state child. Do not infer resume position by sorting for the first
`NOT_RUN` row; blocked/skipped/manual jumps make that heuristic wrong.

## Gotchas And Anti-Patterns

- Do not satisfy step execution by updating `TestCaseStep.actualResult`.
- Do not create a second `ManualRun`, `ExecutionSession`, or status enum that
  duplicates `TestRun` / `TestRunCaseResult` semantics.
- Do not persist dynamic `TestSuite` resolution during runner usage; the run
  already owns the frozen case snapshot.
- Do not store step results, notes, comments, or pause history as unbounded JSON
  or comma-separated values.
- Do not let step-result writes target a test case or step outside the run's
  snapshot, even if the UUID exists in the same project.
- Do not add a client-only runner state that is required for auditability; pass,
  fail, blocked, skip, comments, notes, and timestamps must survive reloads.
- Do not add unsafe rich-text rendering. Notes/comments are Markdown/text by
  convention unless a shared sanitized renderer is introduced.
- Do not add localStorage/sessionStorage for sensitive runner notes. Persist
  through the API and rely on the authenticated server-side audit path.
- Do not expose raw tester names or note/comment text in logs or generic error
  details.
- Do not add a Product / Release / Build / Environment catalog, People
  directory, defect lifecycle, evidence upload pipeline, or automation runner
  under TC-009.

## Non-Goals

- No implementation of TC-009 in this preflight.
- No redesign of `TestRun`, `TestPlan`, `TestSuite`, `TestCase`, or
  `TestCaseStep` beyond the minimum extension needed for runner evidence.
- No collaborative locking, offline-first sync, desktop runner, automation
  execution engine, defect management, evidence upload, graph projection, or
  people/team directory.
- No replacement of the shared auth stack, CSRF flow, audit actor model, error
  envelope, MCP parity policy, frontend API client, or enum mirror discipline.
