# ADR-049: Test Run Entity

## Status

Accepted

## Date

2026-05-18

## Context

TC-008 (Wave 1, MUST) requires the system to provide a Test Run entity:
the execution-time record for one pass through a test suite, with a
unique ID, name, associated test plan, assigned testers, environment,
build/version, status, start/end timestamps, and individual test case
execution results.

The existing test-management aggregates cover authored test definitions
(`TestCase`, `TestCaseStep`, `TestCaseGherkin`, `TestCaseFolder` -
ADR-040 / ADR-041 / ADR-042 / ADR-043), the planning container
(`TestPlan` - ADR-044), and the selection container (`TestSuite` -
ADR-047). None of them is an execution record:

- `TestCase` is authored intent, not a per-pass outcome.
- `TestCaseStep.actualResult` and `TestCaseGherkin` source are authored
  test content, not run-time evidence.
- `TestPlan` groups planning scope and release coordinates; it has no
  membership of cases and no run-time status.
- `TestSuite` is a *rule* for selecting cases (`STATIC`,
  `REQUIREMENTS_BASED`, `QUERY_BASED`); resolution is dynamic at read
  time and not a frozen execution record.
- `VerificationResult` records the outcome of a requirement
  verification activity, not a per-case execution pass.
- `ControlTest` records a control-effectiveness test, with its own
  conclusion vocabulary.

Without an explicit decision, the failure modes are:

- run/result fields creep onto `TestCase`, conflating authored intent
  with execution evidence.
- per-case results land on `TestCaseStep.actualResult`, which is
  authored content not run-time data.
- live query-based suite resolution is treated as the historical
  membership of a run; subsequent suite edits silently rewrite history.
- assigned testers land in `users` / `authorities`, conflating session
  credentials with business assignment provenance (ADR-037 bounds those
  tables to session storage).
- a generic, reused status enum (`TestCaseStatus`, `TestPlanStatus`,
  `VerificationStatus`) is pressed into service even though the words
  describe different lifecycle objects.

The preflight note
`architecture/notes/test-run-entity-preflight.md` records the boundary
and cross-cutting guardrails the design must satisfy; this ADR consumes
that note and records the load-bearing decisions.

## Decision

### 1. TestRun is a separate aggregate from definition, planning, and selection

Introduce a dedicated `TestRun` aggregate inside the existing
`domain/testcases/` boundary. The aggregate is project-scoped,
`@Audited`, references the driving `TestPlan` and `TestSuite` via FKs,
and owns its execution evidence directly via two child aggregates:
`TestRunTesterAssignment` (assigned testers) and `TestRunCaseResult`
(per-case execution outcomes, also the canonical snapshot of cases
included in the run).

A `TestRun` is **not** a test case, test-case step, test-case folder,
test plan, test suite, verification result, control test, defect, or
evidence artifact. The boundary stays sharp.

The aggregate stays inside `domain/testcases/` mirroring ADR-044 /
ADR-047. A future ADR may split a wider `testmanagement` boundary if
the surface keeps growing; that split is not in scope for TC-008.

### 2. Snapshot resolution on the run side

On create, the service resolves the run's `TestSuite` via
`TestSuiteService.resolveTestCases` (capped at 500 by the existing
`MAX_RESOLVED_TEST_CASES`) and snapshots the resulting cases as
`TestRunCaseResult` rows. The snapshot is the canonical membership of
the run. After create, mutations to the source suite (member changes,
criteria edits, mode behavior) **do not** rewrite the run's case set.

The snapshot rows carry the test case's UID and title at snapshot time
(`test_case_uid`, `test_case_title`). Later renames to the linked
`TestCase` do not rewrite historical evidence; reviewers reading the
run see what was selected when the run started.

This satisfies the preflight requirement that dynamic query-based
suite resolution must not become historical execution evidence, and it
gives `TestSuite`'s deliberate "membership is the rule, not the cached
outcome" contract (ADR-047) a stable downstream consumer.

### 3. Dedicated execution-status vocabulary

`TestRunStatus`: `PLANNED`, `IN_PROGRESS`, `COMPLETED`, `ABORTED`,
`ARCHIVED`. Transition graph:

- `PLANNED → IN_PROGRESS | ABORTED | ARCHIVED`
- `IN_PROGRESS → COMPLETED | ABORTED | ARCHIVED`
- `COMPLETED → ARCHIVED` (terminal once archived)
- `ABORTED → ARCHIVED`
- `ARCHIVED → ∅` (terminal soft-delete)

We did **not** reuse `TestCaseStatus` (authored-definition lifecycle),
`TestPlanStatus` (planning lifecycle with backwards arcs for
re-opening a plan), `VerificationStatus`, or `ControlTestConclusion`.
Their words describe different lifecycle objects.

Unlike `TestPlanStatus`, there are **no** backwards arcs out of
`COMPLETED` or `ABORTED`: a run is a single execution pass against a
frozen snapshot. Re-running is a new run with its own identity rather
than a status flip on the prior one. A plan can group many runs; that
multiplicity is the right home for "the team retried after a build
fix," not a status-flip on the prior run.

`TestRunCaseResultStatus`: `NOT_RUN`, `PASSED`, `FAILED`, `BLOCKED`,
`SKIPPED`. Result statuses have **no** transition graph - a tester
may flip a result freely as re-tests, descopes, and unblocks happen
over the life of a run. Validation is "must be a known enum
constant"; the enum is the authority.

### 4. Assigned testers as normalized child rows

Testers are recorded as one `TestRunTesterAssignment` row per tester
per run. Tester names are bounded text (`VARCHAR(120)`), validated for
non-blank, and `(test_run_id, tester_name)` is unique per run.

Testers are **not**:

- entries in the Spring Security `users` table (ADR-037 bounds that
  table to session credentials; adding business-assignment fields
  would conflate session storage with provenance).
- comma-separated text or JSON arrays on `TestRun` (the preflight
  explicitly forbids that - rows must remain queryable, validated, and
  audited).
- audit actors. The audit actor on every mutation is still the
  authenticated principal via `ActorFilter`; assigned testers are the
  *content* of the run, not the *actor* of the operation.

If a future requirement promotes testers into a People / Team
directory, the migration is straightforward: add the directory
aggregate, add a nullable FK column, keep the text column for one
release, then drop it.

### 5. Per-case results as normalized child rows with snapshot fields

`TestRunCaseResult` rows carry:

- `test_run_id` (FK to run, NOT NULL, cascade-delete by service).
- `test_case_id` (FK to case, NOT NULL, restrict-delete via FK).
- `test_case_uid` (`VARCHAR(50)`, NOT NULL) - snapshot at create.
- `test_case_title` (`VARCHAR(200)`, NOT NULL) - snapshot at create.
- `status` (`TestRunCaseResultStatus`, NOT NULL, default `NOT_RUN`).
- `notes` (TEXT, optional).
- `BaseEntity` timestamps mirrored into the audit shadow.

`(test_run_id, test_case_id)` is unique per run - a run carries at
most one result row per snapshotted case.

The snapshot fields are intentional duplication: they preserve case
identity for historical evidence even if `TestCase.uid` is renamed or
`TestCase.title` is rewritten. The FK to the live `TestCase` is kept
so a future "jump to the authored case" affordance has a target; the
snapshot is what reviewers read.

### 6. Release coordinates as bounded scalars

`environment`, `version`, `build` are bounded `VARCHAR(100)` columns
on `TestRun`. Same precedent as `TestPlan`: no Product / Release /
Build / Environment catalog is required by TC-008, and inventing one
here would force a release-management surface we have not been asked
for. A future requirement can promote any of these to a FK without
amending run identity.

### 7. Timestamps, not dates

The TC-008 statement explicitly says "start/end timestamps." Both
columns are `TIMESTAMPTZ` (Java `Instant`). This is distinct from
`TestPlan.startDate` / `endDate` which are `LocalDate` because a plan
schedules a *window* in business-calendar terms; a run records the
actual instants at which testing began and ended.

The `end_at >= start_at` cross-field invariant is enforced atomically
on both create and update, mirroring the `TestPlan.applyScheduleUpdate`
pattern: per-field setters compare against the currently stored
counterpart, so a whole-window shift is reconciled once and applied as
a unit before validation.

### 8. API surface

Routes live under `/api/v1/test-runs/**` and are picked up by the
existing shared `/api/v1/** .authenticated()` rule in
`ApiPathMatrix`; no path-matrix change is required.

- `POST /api/v1/test-runs` - create.
- `GET /api/v1/test-runs` - list (`createdAt DESC`).
- `GET /api/v1/test-runs/{id}` - by UUID.
- `GET /api/v1/test-runs/uid/{uid}` - by project-scoped UID.
- `PUT /api/v1/test-runs/{id}` - partial update with `clearXxx` flags.
- `PUT /api/v1/test-runs/{id}/status` - transition.
- `DELETE /api/v1/test-runs/{id}` - delete (cascades children).
- `POST /api/v1/test-runs/{id}/testers` - add tester.
- `GET /api/v1/test-runs/{id}/testers` - list testers.
- `DELETE /api/v1/test-runs/{id}/testers/{testerName}` - remove tester.
- `GET /api/v1/test-runs/{id}/results` - list per-case results.
- `PUT /api/v1/test-runs/{id}/results/{testCaseId}` - update status + notes.

Mode is not relevant on a run - the suite's mode picks the snapshot
strategy at create, after which the run owns the snapshot. There is no
`/{id}/mode` endpoint.

### 9. Persistence

Six migrations introduce the run root, tester assignments, per-case
results, and their audit shadows:

- V104 `test_run`, V105 `test_run_audit`
- V106 `test_run_tester_assignment`, V107 `test_run_tester_assignment_audit`
- V108 `test_run_case_result`, V109 `test_run_case_result_audit`

Audit shadows omit `project_id` and the parent FKs to `test_plan` /
`test_suite` on `test_run_audit` (`@NotAudited`); the parent FK on the
child audit shadows is retained with
`RelationTargetAuditMode.NOT_AUDITED` (mirroring `test_suite_member_audit`).
`AuditRetentionJob.AUDIT_TABLES` gains the three new shadow tables.
`MigrationSmokeTest.allFlywayMigrationsRan` and the column-/constraint-
existence probes are extended; `RequirementsE2EIntegrationTest`'s
hardcoded version list is extended to V109.

### 10. MCP and frontend mirrors

A new consolidated `gc_test_run` MCP tool exposes the write surface
(actions `create | update | delete | transition | add_tester |
remove_tester | update_result`); reads route through `gc_query` once
`/api/v1/test-runs` is added to its allow-list. `lib.js` exports
`TEST_RUN_STATUSES` and `TEST_RUN_CASE_RESULT_STATUSES` plus typed
wrappers for each endpoint. Frontend `api.ts` mirrors the same
enums and DTO shapes per ADR-034.

## Consequences

- TC-008 is satisfied with one new root aggregate, two child
  aggregates, dedicated execution-status vocabulary, snapshot-based
  membership, and a focused REST + MCP + frontend surface.
- `TestPlan.id` and `TestSuite.id` are engaged as the load-bearing
  extensibility seams ADR-044 and ADR-047 anticipated; no amendments
  to those aggregates are required.
- The selection-vs-planning-vs-organisation-vs-execution vocabulary
  stays clean: `TestRun` is the only aggregate that owns *what
  happened during one pass.*
- Re-running a suite for a new build/version produces a new
  `TestRun`; audit linkage from the prior run survives unchanged.
- Snapshotting on create is the explicit trade-off - once a run is
  created, suite edits do not propagate. That is by design (the
  preflight requires it), and a future "preview against the live
  suite" UI affordance can read the live resolver without touching
  the run's snapshot.
- Future work (automation runner integration, defect linkage,
  evidence upload, bulk result updates) extends `TestRun` /
  `TestRunCaseResult` or adapter classes; it does not bleed back into
  `TestCase` / `TestPlan` / `TestSuite`.

## Alternatives Considered

- **Reuse `TestSuite` and read live resolution as the run.**
  Rejected: ADR-047 deliberately keeps suite membership *dynamic*
  ("the rule, not the cached outcome"). A run is *frozen* execution
  evidence; snapshotting it on the run side is the only way to keep
  both invariants honest.
- **Reuse `TestPlanStatus` for run lifecycle.** Rejected: plan
  lifecycle has backwards arcs (pause / re-open the plan window);
  run lifecycle is a single pass and does not. The shared words
  would mislead consumers about what a status flip means.
- **Reuse `VerificationStatus` or `ControlTestConclusion`.**
  Rejected: their words describe different lifecycle objects
  (requirement verification, control effectiveness).
- **JSON array of results / testers on `TestRun`.** Rejected:
  the preflight forbids it. Rows must be queryable, validated,
  and audited; JSON blobs lose all three.
- **Store testers in `users` / `authorities`.** Rejected:
  ADR-037 bounds those tables to session-credential storage.
  Extending them with business-assignment fields confuses session
  identity with run provenance.
- **Skip the snapshot - read the suite resolver every time.**
  Rejected: a run is supposed to be evidence. Re-resolving the
  live suite turns "what did we run" into "what would we run
  today" and makes audit history fictional.
- **First-class Product / Release / Build / Environment aggregates.**
  Rejected for TC-008: no such requirement exists, and the blast
  radius would block this requirement on unrelated work. The bounded
  scalar pattern from `TestPlan` (ADR-044) is reused; a future
  requirement can promote them with a focused migration.

## Related

- ADR-040 (Test case domain), ADR-041 (Step format), ADR-042
  (Gherkin format), ADR-043 (Hierarchical organisation), ADR-044
  (Test Plan entity), ADR-047 (Test Suite entity) - sibling
  aggregates inside the `testcases` boundary.
- ADR-033 (Authenticated audit actor provenance) - Envers actor
  wiring `TestRun` inherits unchanged.
- ADR-034 (API enum contract single source of truth) -
  `TestRunStatus` / `TestRunCaseResultStatus` are single-sourced in
  the domain `state` package and mirrored in `frontend/src/types/api.ts`
  and `mcp/ground-control/lib.js`.
- ADR-037 (Browser session access control) - the session / CSRF
  chain `/api/v1/test-runs/**` rides through.
- `architecture/notes/test-run-entity-preflight.md` - design input
  for this ADR.
