# Implement Completion Record Consolidation Preflight

Issue #1103 collapses `/implement` Phase D's tail from separate verification,
label cleanup, and final-report steps into one server-verified completion
record. This note is preflight guidance only. It does not change the workflow
steps, MCP tools, or user-facing docs by itself.

## Architecture Boundaries

- Preserve ADR-029's issue-thread gate model. The consolidated record still
  lives on the GitHub issue thread and must contain the same durable evidence
  now produced by the Step 17 assertion markers and Step 19 final report.
- Keep the MCP server as the trust boundary. The new completion record should
  compose existing server-side assertions and the final-report renderer instead
  of moving assertions, GitHub posting, marker creation, or filtering into
  agent prose.
- Treat Step 18 label removal as operational visibility, not completion state.
  Removing it as a mandatory step must not close the issue early, bypass Phase E,
  or make the `in-progress` label part of the completion gate.
- Keep Phase E separate. `gc_close_issue_after_merge` remains the only
  post-merge issue-close path and must still verify the linked PR is merged
  before closing.
- Do not introduce a new durable state store. Existing phase markers, the
  `gc:final-report` marker, and issue comments remain the persistence model
  until a future Temporal implementation owns this workflow end to end.

## Cross-Cutting Concerns to Reuse

- **Traceability assertion:** reuse `runAssertTraceabilityReconciled` /
  `gc_assert_traceability_reconciled`. Do not duplicate status, IMPLEMENTS, or
  TESTS link checks in a second helper.
- **GRC assertion:** reuse `runAssertGrcReconciled` /
  `gc_assert_grc_reconciled`, including its lack of a per-tool override and its
  issue-thread `gc:grc-screening` parser.
- **Final report:** reuse `validateFinalReportInput`, `buildFinalReport`, and
  `runPostFinalReport` checks for CI, SonarCloud, review evidence,
  `plain_english_outcome`, documentation outcome, sensitive content, body size,
  reserved marker injection, and `lane="quickfix"` behavior.
- **Phase prerequisites:** reuse `readCompletedPhases`,
  `evaluatePhasePrerequisite`, `postPhaseMarker`, and the existing phase marker
  format. If the consolidated tool still calls the assertion helpers directly,
  final-report prerequisite checks should either see the markers those helpers
  wrote or use a tightly scoped internal skip that cannot bypass any other
  final-report guard.
- **GitHub side effects:** reuse `ensureGitRepo`, `getOwnerRepo`, paginated
  issue-comment reads, and `gh api` argv-style posting. Do not shell-interpolate
  issue numbers, PR numbers, comments, labels, branch names, or tokens.
- **Policy sync:** update the existing `run_traceability_reconciliation_gate_contract`
  policy check and tests when the step prose changes. A passing policy check
  should prove the consolidated tool, the final report outcome field, and Phase
  E close path remain documented together.

## Security Layers In Scope

- **MCP input schema:** validate positive integer issue and PR numbers,
  `requirements[]` entries with exact Ground Control UIDs, bounded final-report
  strings, final-report status enums, optional `project`, and optional
  `touched_files`. Keep quickfix and implement lane behavior explicit.
- **Repository boundary:** resolve `repo_path` with `ensureGitRepo`; do not let
  caller paths or touched file paths escape the repository. If touched files are
  classified or read, use the existing repo-relative containment helpers.
- **Ground Control API reads:** the traceability and GRC checks must re-fetch
  authoritative state from the REST API. Caller-supplied summaries are report
  inputs, not proof of reconciliation.
- **GitHub posting:** comments must be rendered server-side, filtered through
  reserved-marker and sensitive-content checks, body-size checked, and posted by
  the MCP host using argv-based `gh api`.
- **OS/process exposure:** never pass provider, GitHub, SonarCloud, or Ground
  Control secrets in argv, comment bodies, telemetry records, or returned error
  messages.
- **Error envelopes:** expected gate failures should return stable `{ok:false,
  error, message, next_action}` envelopes with assertion details. They should
  not throw stack traces or silently post a partial completion record.

## Extensibility Guardrails

- The useful seam is an assertion list, not a second workflow engine. Today the
  consolidated tool should run traceability and GRC assertions; the next likely
  variation is adding another server-side completion assertion. Model that as a
  small ordered set of assertion outcomes in the returned envelope and rendered
  record.
- Return one envelope with `assertions[]`, the final-report comment URL/id, and
  any marker URLs/ids produced by the assertions. This leaves room for future
  assertions without changing the caller's control-flow shape.
- If Step 18 cleanup remains optional or best-effort, represent it outside the
  assertion list so readers do not confuse label hygiene with shipping evidence.

## Gotchas and Anti-Patterns

- Do not make the agent manually call `gc_assert_traceability_reconciled`,
  `gc_assert_grc_reconciled`, then `gc_post_final_report` and call that
  "consolidated." The issue asks for one MCP tool call.
- Do not reimplement final-report validation in the new tool. The existing
  runner already owns the CI/Sonar/review/outcome/secret gates.
- Do not weaken `gc_post_final_report` by broadening quickfix or override
  behavior. Any internal bypass used by a composed helper must be unreachable to
  external callers and must not skip CI, SonarCloud, review, or content filters.
- Do not remove `traceability_reconciled` or `grc_reconciled` markers unless an
  ADR explicitly replaces them. Existing Phase E and audit tooling can depend
  on those markers even if they are produced inside a consolidated call.
- Do not close the issue, merge the PR, or remove the Phase E
  `gc_close_issue_after_merge` gate as part of this consolidation.
- Do not add a local run ledger, git notes, database row, telemetry record, or
  branch-keyed state to remember completion. The issue thread remains the
  durable record.

## Non-Goals

- No changes to requirement status transitions or Step 16 reconciliation.
- No redesign of GRC screening or ADR-058's derivation-backed target.
- No new GitHub client abstraction or direct agent-side `gh issue comment`.
- No change to `/quickfix` beyond preserving its existing final-report lane
  behavior.
- No implementation of Temporal, queues, or resumable workflow state.
