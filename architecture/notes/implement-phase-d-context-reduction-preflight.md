# Implement Phase D Context Reduction Preflight

Issue #963 targets the `/implement` tail where requirement transitions,
traceability reconciliation, completion assertions, and final reporting can run
after the parent orchestrator has accumulated the whole implementation
transcript. This note is architecture preflight guidance only. It does not
change the workflow steps, MCP tools, routing config, or issue-close behavior.

## Architecture Boundaries

- Treat the current repo state as the baseline: Phase D is Steps 9 through 17,
  Step 17 calls `gc_assert_completion`, and Phase E Step 20 closes the issue
  only after `gc_close_issue_after_merge` verifies a merged linked PR.
- Preserve ADR-029's durable-record model. Phase markers, traceability/GRC
  reconciliation markers, decision records, and the final report stay on the
  GitHub issue thread. Subagent handoff data, telemetry, or local cache files
  are not workflow state.
- Use ADR-036's routing seam before adding a Phase-D-specific delegation
  mechanism. `transition_reconcile` and `final_report` are stable stage ids;
  `gc_resolve_workflow_route` plus `.ground-control.yaml::routing.stages` is the
  executable place to choose parent versus subagent and model tier.
- Keep the MCP server as the trust boundary for GitHub writes, phase markers,
  final-report rendering, secret filtering, and merge-verified issue close.
  A Phase D worker may call MCP tools; it must not replace those tools with
  agent-authored `gh issue comment`, `gh issue close`, or free-form Markdown.
- Do not conflate the two halves of the issue. Reducing pre-merge Phase D
  context cost is separate from post-merge close ordering. The unsafe early
  close path is already handled by Phase E; any future merge-triggered
  mechanism must not re-close, re-report, or bypass `gc_close_issue_after_merge`.

## Cross-Cutting Concerns to Reuse

- **Routing/config:** reuse `parseGroundControlYaml`,
  `normalizeRoutingConfig`, `DEFAULT_IMPLEMENT_ROUTING_STAGES`, and
  `runResolveWorkflowRoute`. Do not create a second routing table in step
  prose, docs, or driver-local code.
- **Telemetry:** reuse `gc_log_step_telemetry`, `buildTelemetryRecord`,
  `buildTelemetryRelPath`, and `sanitizeTelemetryBranch`. Telemetry remains
  operational measurement only and must not gate Phase D or Phase E.
- **Completion record:** reuse `runAssertCompletion`, which composes
  `runAssertTraceabilityReconciled`, `runAssertGrcReconciled`, and
  `runPostFinalReport`. Do not split Step 17 back into separate agent-managed
  report/assertion calls.
- **GitHub side effects:** reuse `ensureGitRepo`, `getOwnerRepo`, paginated
  issue-comment reads, sensitive-content checks, reserved-marker checks, and
  argv-based `gh api` posting in the MCP layer.
- **Traceability semantics:** reuse Step 15's transition ordering and Step 16's
  reconciliation/backfill rules. A compact handoff envelope may cache
  `in_scope_requirements[]`, touched files, review summaries, CI/Sonar status,
  and Step 15 backfill targets; it must not summarize away requirement status,
  link type, or artifact-type decisions that Ground Control validates.
- **Policy sync:** if workflow prose or routing behavior changes, keep
  `skills/implement/SKILL.md`, the affected step files,
  `docs/DEVELOPMENT_WORKFLOW.md`, `docs/WORKFLOW.md`, ADR-021/ADR-029/ADR-036,
  and `tools/policy/checks.py` in sync through the existing policy tests.

## Security and Validation Layers

- **MCP schemas:** any new or widened tool input must validate positive
  issue/PR ids, exact requirement UIDs, bounded status enums, bounded summary
  strings, repo-relative touched files, and explicit override reasons where an
  existing tool already requires them.
- **Repository containment:** diff paths, telemetry paths, and any cache paths
  must pass the existing repo-relative and realpath-containment helpers. Branch
  names and issue titles must never become raw path segments.
- **Ground Control API authority:** caller-provided reconciliation summaries are
  report inputs, not proof. `gc_assert_completion` must continue to re-fetch
  authoritative requirement, traceability, and GRC state before publishing the
  final report.
- **GitHub posting:** comments and PR bodies must be rendered by MCP tools from
  structured input, filtered for secrets and reserved markers, size-checked,
  and posted through the host-side argv `gh api` path.
- **OS/process exposure:** do not put GitHub, Ground Control, SonarCloud, or LLM
  provider tokens in argv, telemetry records, cache files, comments, PR bodies,
  or returned error messages.
- **Error envelopes:** expected Phase D failures should return stable
  `{ok:false, error, message, next_action}`-style envelopes. Do not throw raw
  stack traces or publish partial completion comments on failed gates.

## Extensibility Guardrails

- The useful immediate seam is a compact Phase D execution envelope keyed by
  issue, PR, branch, requirement UIDs, touched files, review summaries,
  CI/Sonar status, plan-comment URL, and prior Step 15 backfill data. Keep that
  envelope structured and bounded so it can be consumed by a subagent today or
  a future worker/activity without changing the record model.
- The future zero-agent-cost seam is server-side orchestration over existing
  MCP helpers, not a GitHub Action that reimplements Ground Control rules in
  shell. If a merge-triggered mechanism is introduced later, it should be
  authenticated, idempotent, issue/PR-linked, and narrow: derive the merge
  event envelope, call Ground Control/MCP server-side logic, and write the same
  issue-thread markers.
- Keep provider choice at the routing edge. Do not bake Claude, Codex, or
  Cursor-specific behavior into Phase D semantics; drivers with no subagent
  support may still execute inline while preserving the same stage ids and
  tool calls.

## Gotchas and Anti-Patterns

- Do not resurrect Steps 18/19 as active Phase D steps. They are tombstoned by
  the Step 17 consolidation, and Step 20 owns post-merge close.
- Do not route async poll-loop stages by analogy. The repo explicitly keeps
  `architecture_preflight`, `review_cycle_1_consume`, and
  `test_quality_review` on the parent because their poll notifications cannot
  resume inside dispatched subagents.
- Do not make subagents the enforcement boundary. They reduce context; MCP
  tools enforce markers, gates, schemas, GitHub writes, and close safety.
- Do not compare every requirement to every file during reconciliation. Step
  15/16 require subject-area-bounded discovery and validated artifact reads.
- Do not treat GitHub's `Closes #n` keyword as sufficient close enforcement.
  It is a UI cross-link and auto-close convenience; Phase E's merge-verified
  tool remains the idempotent backup and structural gate.
- Do not add a new database table, local ledger, git note, branch-keyed state,
  or telemetry-derived completion state for Phase D.

## Non-Goals

- No change to the one-human-touchpoint model: the user still owns PR merge.
- No agent-side PR merge or pre-merge issue close.
- No replacement for `gc_assert_completion`, `gc_close_issue_after_merge`,
  `gc_render_pr_body`, `gc_post_decision_record`, or existing traceability
  reconciliation tools.
- No backend REST controller, GitHub webhook, GitHub Action, Temporal workflow,
  queue, or worker implementation in this scope.
- No change to GRC screening/reconciliation semantics beyond preserving the
  existing `gc_assert_grc_reconciled` gate inside Step 17.
