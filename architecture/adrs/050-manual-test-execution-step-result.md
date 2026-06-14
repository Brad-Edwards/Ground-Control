# ADR-050: Manual Test Execution Step Result

## Status

Accepted

## Date

2026-05-19

## Context

TC-009 (Wave 1, MUST) requires a browser-based test execution runner over
the existing `TestRun` aggregate (ADR-049). The runner must support
step-by-step execution with `PASSED` / `FAILED` / `BLOCKED` / `SKIPPED` per
step, overall test result status, pause and resume, comments/notes per step
and per test, and execution timestamps.

ADR-049 already covers per-case execution evidence via `TestRunCaseResult`,
including a `notes` field and a five-state result vocabulary
(`TestRunCaseResultStatus`). What it does not cover is **per-step**
execution evidence: a snapshotted record of each authored `TestCaseStep`
with its own runtime status, comment, and timestamp.

The preflight note
`architecture/notes/manual-test-execution-runner-preflight.md` records the
binding boundary and cross-cutting guardrails the design must satisfy. This
ADR consumes that note and records the load-bearing decisions about (a) the
new run-side step-result entity, (b) the pause/resume cursor, and (c) what
TC-009 deliberately does NOT add.

Without an explicit decision the failure modes are:

- runner evidence creeps onto `TestCaseStep.actualResult`, conflating
  authored content with run-time data - exactly the boundary ADR-041
  defined `actualResult` to keep clear.
- a parallel `ExecutionSession` / `ManualRun` domain is invented even though
  the run aggregate already has frozen membership, status semantics, and
  audit infrastructure.
- pause is modeled as a new `TestRunStatus` value, splitting state space
  with no semantic gain (a paused run is still an in-progress run with
  retained cursor data).
- a new five-state status enum is invented for steps even though the
  vocabulary is identical to the case-level outcomes; the ADR-034
  single-source contract is broken across two parallel enums.
- step evidence is stored as a JSON blob on the case-result row, sacrificing
  queryability, retention, and audit history.
- the cursor is audited on every step interaction, blowing up the
  `test_run_audit` table with one revision per step recorded.

## Decision

### 1. `TestRunStepResult` is a new run-side aggregate-root child

A new entity, normalized one row per snapshotted step per case-result, is
created at run-create time alongside the existing `TestRunCaseResult`
snapshot. The entity is `@Audited`, has its own `_audit` shadow table, and
sits below `TestRunCaseResult` in the cascade tree.

Fields:

- `testRunCaseResult` - `@Audited(NOT_AUDITED)` `@ManyToOne` parent. The
  audit shadow stores the FK value but Envers does not audit the parent
  entity reference itself; the same pattern `TestRunCaseResult` uses for
  its run / case parents.
- `testCaseStep` - `@Audited(NOT_AUDITED)` `@ManyToOne` to the authored
  step. Kept as a live FK so join queries can correlate evidence to the
  step's identity; the snapshot fields are authoritative for replay.
- `stepNumberSnapshot`, `actionSnapshot`, `expectedResultSnapshot`,
  `snapshotOrder` - authored content captured at run-create time. Later
  edits to the authored step never rewrite a run's historical evidence
  (mirrors the `TestRunCaseResult.testCaseUid` / `testCaseTitle` snapshot
  semantics).
- `status` - runtime outcome; reuses `TestRunCaseResultStatus` (see §2).
- `comment` - per-step tester note (TEXT, nullable). User evidence.
- `executedAt` - `Instant` (nullable). Set by the runner when the tester
  records a non-`NOT_RUN` status; cleared by the explicit clear flag.

The table carries unique constraints on
`(test_run_case_result_id, test_case_step_id)` (one row per step per
case-result) and `(test_run_case_result_id, snapshot_order)` (preserves
authored order on read). A CHECK constraint on `status` backs the enum at
the SQL layer.

### 2. Step status reuses `TestRunCaseResultStatus`

The five outcomes (`NOT_RUN`, `PASSED`, `FAILED`, `BLOCKED`, `SKIPPED`) are
identical at step level and at case level. Inventing a parallel enum would
split documentation, the SQL CHECK, the frontend mirror, and the MCP enum
export for no semantic gain, violating the ADR-034 enum-contract.

Step status flips are unconstrained (same convention as
`TestRunCaseResult.status`) - a tester may flip PASSED → FAILED on re-test
or BLOCKED → SKIPPED on descope.

### 3. Snapshot happens at run-create time, not on first interaction

`TestRunService.create()` snapshots every authored step of every resolved
case in the same transactional moment as the case-result snapshot. Cases
with zero authored steps yield zero step-result rows. The MAX_RESOLVED
cap (500 cases) bounds the upfront write cost; at typical step counts the
extra rows are inconsequential.

The alternative - snapshot lazily on first interaction with a case - was
rejected because (a) it requires special "first-touch" logic in the
service, (b) authored steps can be edited between run-create and
first-interaction in ways that change the snapshot the tester sees, and
(c) the case-result snapshot already established the precedent of
eager-snapshot-at-create.

### 4. Pause and resume use a non-audited cursor on `TestRun`

Two nullable UUID columns on `test_run` - `current_case_result_id` and
`current_step_result_id` - capture "where the tester left off." They are
`@NotAudited` so cursor movement does not produce a `test_run_audit`
revision; if it did, every step recorded would write a redundant run
revision, bloating the audit log with no compliance value (the per-step
revisions on `test_run_step_result_audit` already record what changed).

The cursor references rows by identity only - no FK constraint, no entity
mapping. Deleting a stale case-result or step-result must not be blocked by
a stale cursor pointer, and the service layer null-handles a cursor that no
longer resolves.

Pause is the implicit state where the tester closed the tab. Resume reads
the cursor on mount and selects the corresponding case + step.

The alternative - extend `TestRunStatus` with a `PAUSED` value - was
rejected because (a) a paused run is still an in-progress run from the
lifecycle perspective (no new transition arcs, no new terminal semantics),
and (b) the new state would have to be mirrored in `TestRunStatus`'s
transition graph, the SQL CHECK, the frontend enum, and the MCP export -
expensive for no functional gain. The cursor design carries pause as
runner state, not lifecycle state.

### 5. Per-case status remains a separate manual update

TC-009 does NOT auto-rollup per-case status from per-step status. A tester
may legitimately mark a case `BLOCKED` even when some steps `PASSED` (the
last step's preconditions failed), or `PASSED` when all steps observed but
one was `SKIPPED` (out of scope). Forcing a rollup constraint would
reduce expressive power for no observability gain.

The case-level update endpoint shipped in TC-008
(`PUT /api/v1/test-runs/{id}/results/{testCaseId}`) is the runner's
case-status surface; the new step-level endpoint
(`PUT .../results/{caseResultId}/steps/{stepResultId}`) is independent.

### 6. REST surface lives under `/api/v1/test-runs/**`

Three new endpoints extend `TestRunController`:

- `GET /api/v1/test-runs/{id}/results/{caseResultId}/steps` - list the
  step-result rows for a case, ordered by `snapshot_order`.
- `PUT /api/v1/test-runs/{id}/results/{caseResultId}/steps/{stepResultId}`
 - record/update a single step result (status required; comment +
  `executedAt` optional with explicit clear flags).
- `PUT /api/v1/test-runs/{id}/cursor` - set / clear the pause-resume
  cursor.

Keeping them under the existing `/api/v1/test-runs/**` glob means the
`ApiPathMatrix` bearer/session auth chain applies unchanged - no
runner-local auth.

## Consequences

### Positive

- Per-step evidence is queryable, validated at the DTO + entity + SQL
  layers, and audited end-to-end. The runner can reconstruct a historical
  run exactly as the tester observed it.
- Authored `TestCaseStep` remains definition-time content (ADR-041
  invariant preserved). Renumbering, action edits, and expected-result
  edits never rewrite a run's historical evidence.
- The cursor adds resume capability with zero audit-log bloat. Closing the
  tab mid-run is a recoverable interruption rather than a lost session.
- Future extensions - attachments, defect linkage, multi-tester handoff,
  autosave heuristics - extend the run-side step-result row without
  changing authored `TestCaseStep` or re-resolving `TestSuite`.

### Negative

- Run-create writes scale with `O(cases × avg_steps_per_case)`. With the
  existing 500-case cap and typical step counts in the 5-15 range, this is
  a few thousand inserts per run-create - well within an ORM transaction.
  If step counts grow large enough to matter, the snapshot loop is
  trivially batched.
- A new audit-shadow table joins the `AuditRetentionJob` cleanup list. One
  more entry in `AUDIT_TABLES` for the daily retention sweep; bounded
  cost.
- The cursor is intentionally not FK-constrained. A historical
  inconsistency (cursor pointer to a deleted case-result) is the runner
  UI's null-handling problem rather than a database-level invariant.

### Neutral

- Per-case status remains manual. A future requirement may add an
  optional auto-rollup mode without changing the data model.
- The cursor design carries no concurrency model. Multi-tester runs (out
  of TC-009 scope) would need lock state added on top; this ADR records
  no opinion on that future model.

## Alternatives Considered

### A. Step evidence as a JSON blob on `TestRunCaseResult`

Rejected. A JSON blob defeats query-by-status, validation, retention, and
audit history - the four properties this whole aggregate exists to provide.

### B. Lazy snapshot on first step-result interaction

Rejected. See §3. Inconsistent with the eager case-result snapshot
precedent, and exposes a window where the tester sees one set of authored
steps and the persisted snapshot reflects a different set.

### C. Parallel `TestRunStepResultStatus` enum

Rejected. Same vocabulary as `TestRunCaseResultStatus`; an extra enum
splits ADR-034's single-source contract for no semantic gain.

### D. Extend `TestRunStatus` with `PAUSED`

Rejected. See §4. Pause is runner state, not lifecycle state.

### E. Cursor as a separate `test_run_runner_state` child table

Rejected for the MVP - two columns on `test_run` is simpler. Reconsider if
multi-tester runs need per-tester cursor state, which would push the
cursor to a child row keyed by tester.

## References

- ADR-049 - Test Run Entity. This ADR builds directly on §1 (run as a
  separate aggregate), §2 (snapshot resolution on the run side), and §3
  (dedicated execution-status vocabulary).
- ADR-041 - Test Case Step Format. The "authored content is not run-time
  evidence" boundary is the load-bearing reason TC-009 cannot write into
  `TestCaseStep.actualResult`.
- ADR-034 - API Enum Contract: Single Source. The reason step status
  reuses the case-level enum rather than inventing a parallel one.
- ADR-037 - Browser Session Access Control. The shared CSRF chain the
  runner UI uses for all writes.
- `architecture/notes/manual-test-execution-runner-preflight.md` - binding
  guardrails this ADR consumes.
