---
name: implement
description: End-to-end issue implementation - from plan through merged PR. Agent-neutral (Claude Code, Codex, Cursor CLI). Parameterized by .ground-control.yaml. Thin orchestrator that delegates per-step work to subagents per ADR-036 + issue #934.
argument-hint: <issue-number | requirement-uid>
disable-model-invocation: true
---

# Implement (orchestrator): $ARGUMENTS

This skill is the canonical, agent-neutral implementation of the Ground Control `/implement` workflow. It runs from Claude Code, Codex, or Cursor CLI against the same content, with repo-specific values supplied by `gc_get_repo_ground_control_context` (per ADR-027).

The workflow handles the entire lifecycle: plan, implement, verify, commit, push, PR, CI, reviews, fix, requirement transitions, traceability reconciliation. **The user's only synchronous touchpoint is PR merge** (per ADR-029). Plans, review findings, and decisions on findings are recorded as comments on the GitHub issue thread so the durable record survives PR merge/close.

`$ARGUMENTS` may be either a GitHub issue number OR a Ground Control requirement UID; in the UID case Step 1 finds or creates the matching issue and runs against it. Bug fixes, refactors, dependency updates, and other requirement-free work enter the same workflow via an issue with zero requirements in scope.

**Templating convention.** Where the prose below references a path or value with `{cfg.X|default Y}`, the agent reads `cfg` from `gc_get_repo_ground_control_context`'s response (Step 1) and substitutes `cfg.X` if non-null, else `Y`.

---

## Step contract (issue #934)

This SKILL is a thin orchestrator. The 716-line monolithic prose that used to live here now lives one file per step under `skills/implement/steps/step-NN-<id>.md`. The canonical review-loop rules live at `skills/implement/steps/_review-loop-rules.md` (Step 6.5 and Step 6.6 reference it; do not restate elsewhere). Update length follows the canonical succinctness rule in `skills/implement/steps/_review-loop-rules.md`.

**For each step in the list below**, the orchestrator does the following:

1. Resolve the route through the `gc_resolve_workflow_route` MCP tool using the stage id (left column of the table). The resolver reads `.ground-control.yaml` and returns `{provider, agent, model, tier, fallback_policy}`. If routing is disabled or unavailable, follow the returned fallback policy.
2. **If `agent: subagent`**: spawn an `Agent` (or driver-equivalent) subagent with the resolved model. The subagent's prompt is verbatim: *"Execute `skills/implement/steps/step-NN-<id>.md` against issue {issue_number}. Cached state from prior steps: `{cached_state_json}`. Return a single short envelope `{status, cached_for_next_step}` and nothing else."* Await the envelope.
3. **If `agent: parent`**: read the step file inline and execute. The parent runs the step locally.
4. **Telemetry**: when `cfg.telemetry.enabled` is true, call `gc_log_step_telemetry` at the end of the step with `{step, tier, model, wall_time_ms, outcome, input_tokens: null, output_tokens: null}`. `wall_time_ms` is measured around the dispatch.
5. Merge the returned `cached_for_next_step` fields into the running state passed to the next step.

The parent never sees verbatim subagent prose, raw `gh`/`git` output, full file contents, raw CI logs, raw Sonar payloads, or per-finding review bodies; those stay in the subagent's context or server-side in the MCP tool layer.

## Step list (in order)

| # | Stage id | File |
|---|----------|------|
| 1 | `issue_branch_resolution` | `steps/step-01-issue-branch-resolution.md` |
| 2 | `read_issue_context` | `steps/step-02-read-issue-context.md` |
| 2.5 | `architecture_preflight` | `steps/step-02.5-architecture-preflight.md` |
| 3 | `codebase_assessment` | `steps/step-03-codebase-assessment.md` |
| 3.5 | `grc_screening` | `steps/step-03.5-grc-screening.md` |
| 4 | `planning` | `steps/step-04-planning.md` |
| 4.4 | `implementation` | `steps/step-04.4-tdd.md` |
| 4.5 | `clause_mapping` | `steps/step-04.5-clause-mapping.md` |
| 5 | `precommit` | `steps/step-05-quality-assurance.md` |
| 6 | `completion_gate` | `steps/step-06-completion-gate.md` |
| 6.5 | `review_cycle_1_consume` | `steps/step-06.5-codex-review.md` |
| 6.6 | `test_quality_review` | `steps/step-06.6-test-quality-review.md` |
| 7 | `git_publish` | `steps/step-07-stage-precommit.md` |
| 8 | `git_publish` | `steps/step-08-commit-push.md` |
| 9 | `pr_body` | `steps/step-09-pr-body.md` |
| 10 | `ci_monitor` | `steps/step-10-ci-monitor.md` |
| 11 | `sonarcloud` | `steps/step-11-sonarcloud.md` |
| 15 | `transition_reconcile` | `steps/step-15-transition.md` |
| 16 | `transition_reconcile` | `steps/step-16-reconcile.md` |
| 17 | `final_report` | `steps/step-17-completion.md` |
| 20 | `close_issue_after_merge` | `steps/step-20-close-issue-on-merge.md` |

Steps 12, 13, 14, 18, 19 are intentional tombstones (post-push Codex review collapsed into pre-push Step 6.5 by #804; post-PR test-quality review moved pre-push to Step 6.6 by #906; final CI re-verify collapsed into Step 10's existing CI watch; Steps 18 and 19 collapsed into the consolidated Step 17 completion tool by #1103). The numbering is preserved so external references (ADR text, policy rules, docs) don't need to track a moving target.

## Phase boundaries (control flow)

- **Phase A** (Steps 1 → 4.5), **Phase B** (Steps 5 → 6.6), **Phase C** (Steps 7 → 8), **Phase D** (Steps 9 → 11, then Step 17 with `phase="pre_merge"`), and **Phase E** (Steps 15 → 16 → Step 17 with `phase="post_merge"` → Step 20) run in fixed order. Phase D ends at a pre-merge **readiness** record and STOPS for the user to review and merge. **Phase E is the post-merge phase** (issue #963 extends #1058): the requirement `DRAFT→ACTIVE` transition (Step 15), traceability reconciliation (Step 16), the reconciled final report (Step 17 `post_merge`), and the issue close (Step 20) all run **only after the PR merges**, so Ground Control state never runs ahead of shipped code. See "Post-merge reconciliation ordering" and "Issue close mechanism" below.
- **Step 4 work-already-complete branch**: when Step 4's envelope returns `work_already_complete: true`, skip Steps 4.4 / 4.5 / 5 / 6 / 6.5 / 6.6 / 7 / 8 / 9 / 10 / 11 (there's no diff to push) and jump to Step 15 to reconcile Ground Control state. This branch has **no PR** - the code it documents already shipped under an earlier merge - so its Step 15/16 transition + reconcile run immediately (the post-merge gate is for *this* run's PR, and there is none); it records state via Step 4's completion comment rather than the merge-gated Step 17 `post_merge` report.
- **Step 10 CI failure**: on `ci_conclusion != "success"`, fix locally, return to Step 7 (re-stage), Step 8 (commit + push), then Step 10 again.
- **Step 11 SonarCloud findings**: same loop - fix, push, re-run Step 10, then Step 11. Cap: 5 SonarCloud iterations.
- **Steps 6.5 / 6.6 escalated or capped**: STOP and wait for the user. The label set in Step 1 stays until the issue is closed at Phase E. Do not push commits while waiting.
- **Post-merge reconciliation ordering (issue #963)**: the requirement status transition (Step 15), traceability reconciliation (Step 16), and the reconciled completion record (Step 17 `phase="post_merge"`) run in **Phase E, after the PR merges** - never pre-merge. Phase D's terminal signal is Step 17 with `phase="pre_merge"`: a **readiness** record (carrying a `ready_for_review` phase marker - *not* a `gc:final-report` marker - that asserts the Step 3.5 GRC screening record exists but does NOT run the traceability assertion, since the requirement is still DRAFT and links do not exist yet) that means "all automated gates are green; ready for the user to review and merge." `gc_assert_completion` with `phase="post_merge"` is **merge-gated** - it refuses with `completion_pr_not_merged` unless the linked PR is merged (`merged_at` non-null AND state `MERGED`) - then sequences the traceability-reconciliation assertion (`traceability_reconciled` marker), the GRC-reconciliation assertion (`grc_reconciled` marker), and `gc_post_final_report` in one deterministic call. Both markers are enforced server-side; without them the post-merge final report refuses. The post-merge final report must reflect the reconciled Ground Control graph; the pre-merge readiness record must not claim a reconciliation that has not happened. Do not surface any earlier user-facing "complete" message - Phase D's readiness record is "ready to merge," not "done"; escalations before Phase E are because input is needed, not because the workflow is finished.
- **Plain-English outcome (issue #1156)**: both Step 17 invocations pass `plain_english_outcome` to `gc_assert_completion`. The tool renders it as the outcome section before the structured evidence, so both the pre-merge readiness record and the post-merge final report explain what the change means in product/operator terms without weakening CI, SonarCloud, traceability, or GRC gates.
- **Issue close mechanism (issue #1058 + #1156)**: the GitHub issue is closed at **Phase E (Step 20)** by `gc_close_issue_after_merge`, which verifies the linked PR is merged (`merged_at` non-null AND state `MERGED`) before running the close. The `Closes #<issue-number>` keyword in the PR body (rendered by `gc_render_pr_body` in Step 9) remains as the GitHub UI cross-link and may auto-close the issue at merge time; Step 20 is the idempotent backup that no-ops when the issue is already closed. **Because reconciliation now runs post-merge (issue #963), the issue may auto-close at merge BEFORE Phase E runs — that is expected and safe.** Phase E's transition (Step 15), reconciliation (Step 16), and final report (Step 17) operate on the requirement graph and the issue thread regardless of the issue's open/closed state (you can transition a requirement and comment on a closed issue), so the auto-close does not block Phase E; Step 20 then no-ops. The `in-progress` label is operational-only; its removal is optional best-effort and no longer a mandatory step gate (#1103). Closing from the agent without the merge-verification gate decouples the close from the merge - an unmerged or rolled-back PR would leave a closed issue with no shipped code behind it, and GitHub does not re-open on revert. Phase E is invoked by re-running `/implement <issue>` after the merge; Step 1's orchestrator detects the Phase-D-complete state - the `ready_for_review` marker present + a merged linked PR + post-merge reconciliation not yet recorded (no `gc:final-report` marker) - and short-circuits to **Step 15** (the start of Phase E: transition → reconcile → post-merge report → close), not back into Phase A–D. The detection keys on the reconciliation-marker state, NOT on the issue being open, precisely because the issue may have auto-closed at merge. If the PR exists but is not yet merged, Phase D is complete and the run STOPS awaiting the user's merge; it does not redo Phase A–D. On successful close, the tool returns `next_issue_recommendation` when a credible open issue is available, or an explicit no-recommendation/failure reason when lookup cannot produce one.
- **Single human touchpoint**: PR merge (between Phase D and Phase E). The orchestrator never runs `gh pr merge`. Phase E runs autonomously when the user re-invokes `/implement` after the merge - there is no synchronous user gate inside Phase E.

## Review-cycle subagent contract (Steps 6.5 and 6.6)

These two steps differ from the rest: each is driven by **one subagent invocation** that owns the full review-fix loop (issue #934 item 2). The subagent prompt lives inside the step file (`steps/step-06.5-codex-review.md` and `steps/step-06.6-test-quality-review.md`). The subagent uses the new cycle wrappers (`gc_codex_review_cycle` / `gc_test_quality_review_cycle`, issue #934 item 3) and returns this envelope:

```json
{
  "status": "clean" | "escalated" | "capped",
  "cycles_run": <int>,
  "summary": "<one-line>",
  "commit_shas": [],
  "decision_record_urls": [ "<url>" ],
  "escalation_reason": null
}
```

Verbatim review prose, per-finding bodies, and raw cycle-tool envelopes never reach the parent.

## Per-step model routing (ADR-036)

Routing is opt-in per repo via `routing.enabled` in `.ground-control.yaml` (default `false`). When the knob is off, every step runs on the parent session's model and this section is advisory. The `tier` annotation on each step file is the provider-neutral capability hint; the resolver maps it to a concrete model.

**Claude tier mapping** (canonical): `low` → `claude-haiku-4-5`, `medium` → `claude-sonnet-4-6`, `high` → `claude-opus-4-8` (the parent - no subagent spawn).

**Codex** (and other drivers without subagent-with-model support): ignore the tier annotation and run every step on the session model. The contract is forward-compatible - a future router consumes the same step-id + tier hints without changing this SKILL.

**Cursor CLI** (issue #1189): same as Codex - the parent session runs every step inline. Do not spawn Claude-model subagents via `gc_resolve_workflow_route`; ignore tier/model delegation. Poll-loop stages (`architecture_preflight`, `review_cycle_1_consume`, `test_quality_review`) MUST stay on the parent session (issue #1168). For Steps 6.5 and 6.6, the parent executes the subagent prompt in the step file directly rather than dispatching a separate agent. Invoke from the repo root in Agent chat: `/implement <issue-number | requirement-uid>`; in CLI: `agent "/implement <issue-number | requirement-uid>"`. Skill discovery: run `bin/install-skills.sh` once on the host (hard-copies into `~/.cursor/skills/<name>`; symlinks fail Cursor's root check). Ground-Control repos also ship a project wrapper at `.cursor/skills/implement/SKILL.md` (real file, not a symlink). CLI permissions live in `.cursor/cli.json`; use `--force` when approval prompts would block a long autonomous run.

## Telemetry (ADR-036)

When `telemetry.enabled` is true, the orchestrator calls `gc_log_step_telemetry` at the end of every routed step. The writer appends one JSONL line to `.gc/telemetry/<issue>-<sanitized-branch>.jsonl` (gitignored, repo-relative, containment-validated). `wall_time_ms` is mandatory and measured by the orchestrator around its dispatch. `input_tokens` / `output_tokens` are `null` when the harness does not surface them (Claude Code today). Telemetry is operational measurement only - it never gates any phase, never replaces the issue thread as the durable record, and never feeds back into the cycle-cap counter.
