---
name: implement
description: End-to-end issue implementation - from plan through merged PR. Agent-neutral (Claude Code, Codex, Cursor CLI). Parameterized by .ground-control.yaml. Primary-session orchestrator with structurally enforced MCP gates.
argument-hint: <issue-number | requirement-uid>
disable-model-invocation: true
---

# Implement (orchestrator): $ARGUMENTS

## Development principles — load before every other action

The parent's first executable action is to read
[`_development-principles.md`](./_development-principles.md) in full. This
happens before configuration lookup, route resolution, issue resolution,
branch handling, or workflow execution.

Capture the canonical invocation root at the same time and create the
parent-owned immutable execution contract:

```json
{
  "schema": "gc.implement.execution-contract/v1",
  "principles_source": "skills/implement/_development-principles.md",
  "principles_sha256": "<sha256 of the exact UTF-8 file bytes>",
  "invocation_root": "<canonical absolute checkout root>",
  "checkout_mode": "same_checkout"
}
```

Cache this object as `execution_contract` and the full file contents as
`development_principles_verbatim`. The parent validates both before every
step. Returned step state may add fields but must never replace
`execution_contract`, `development_principles_verbatim`, `invocation_root`, or
`checkout_mode`; discard an attempted replacement and fail the step with
`execution_contract_mutation_attempt`.

This skill is the canonical, agent-neutral implementation of the Ground Control `/implement` workflow. It runs from Claude Code, Codex, or Cursor CLI against the same content, with repo-specific values supplied by `gc_get_repo_ground_control_context` (per ADR-027).

The workflow handles the entire lifecycle: plan, implement, verify, commit, push, PR, CI, reviews, fix, requirement transitions, traceability reconciliation. **The user's only synchronous touchpoint is PR merge** (per ADR-029). Plans, review findings, and decisions on findings are recorded as comments on the GitHub issue thread so the durable record survives PR merge/close.

`$ARGUMENTS` may be either a GitHub issue number OR a Ground Control requirement UID; in the UID case Step 1 finds or creates the matching issue and runs against it. Bug fixes, refactors, dependency updates, and other requirement-free work enter the same workflow via an issue with zero requirements in scope.

**Templating convention.** Where the prose below references a path or value with `{cfg.X|default Y}`, the agent reads `cfg` from `gc_get_repo_ground_control_context`'s response (Step 1) and substitutes `cfg.X` if non-null, else `Y`.

---

## Step contract

This SKILL is a thin primary-session orchestrator. Step instructions live one
file per step under `skills/implement/steps/step-NN-<id>.md`. The canonical
review-loop rules live at `skills/implement/steps/_review-loop-rules.md`
(Step 6.5 and Step 6.6 reference it; do not restate elsewhere).

**For each step in the list below**, the orchestrator does the following:

1. Revalidate the immutable execution contract and principles digest. Resolve
   advisory stage metadata through `gc_resolve_workflow_route`; it returns
   `{provider, model, tier}` and never selects an executor.
2. Read the step file and execute it in the primary invocation session.
   Ground Control does not manufacture subagents for routine development,
   review, polling, or context containment. A driver may delegate when the user
   explicitly requests it or runtime circumstances independently justify it,
   but delegation is outside this routing contract.
3. When telemetry is enabled, call `gc_log_step_telemetry` with the advisory
   tier/model and measured wall time.
4. Reject a successful envelope that reports only instruction reading,
   inspection, planning, acknowledgment, or partial progress. Validate the
   primary-owned execution contract, then merge the remaining cached state.

Keep raw CI/Sonar payloads and verbatim review findings server-side in their
MCP records. The primary consumes compact structured envelopes.

## Step list (in order)

| # | Stage id | File |
|---|----------|------|
| 1 | `issue_branch_resolution` | `steps/step-01-issue-branch-resolution.md` |
| 2 | `read_issue_context` | `steps/step-02-read-issue-context.md` |
| 2.5 | `architecture_preflight` | `steps/step-02.5-architecture-preflight.md` |
| 3 | `codebase_assessment` | `steps/step-03-codebase-assessment.md` |
| 4 | `planning` | `steps/step-04-planning.md` |
| 4.4 | `implementation` | `steps/step-04.4-tdd.md` |
| 4.5 | `clause_mapping` | `steps/step-04.5-clause-mapping.md` |
| 5 | `precommit` | `steps/step-05-quality-assurance.md` |
| 6 | `completion_gate` | `steps/step-06-completion-gate.md` |
| 6.5 | `review_cycle_1_consume` | `steps/step-06.5-codex-review.md` |
| 6.6 | `test_quality_review` | `steps/step-06.6-test-quality-review.md` |
| 7 | `git_publish` | `steps/step-07-stage-precommit.md` |
| 8 | `git_publish` | `steps/step-08-commit-push.md` |
| 8.5 | `base_sync` | `steps/step-08.5-sync-base.md` |
| 9 | `pr_body` | `steps/step-09-pr-body.md` |
| 10 | `ci_monitor` | `steps/step-10-ci-monitor.md` |
| 11 | `sonarcloud` | `steps/step-11-sonarcloud.md` |
| 15 | `transition_reconcile` | `steps/step-15-transition.md` |
| 16 | `transition_reconcile` | `steps/step-16-reconcile.md` |
| 17 | `final_report` | `steps/step-17-completion.md` |
| 20 | `close_issue_after_merge` | `steps/step-20-close-issue-on-merge.md` |

Steps 3.5, 12, 13, 14, 18, 19 are intentional tombstones (Step 3.5 GRC screening retired by ADR-089/#1346; post-push Codex review collapsed into pre-push Step 6.5 by #804; post-PR test-quality review moved pre-push to Step 6.6 by #906; final CI re-verify collapsed into Step 10's existing CI watch; Steps 18 and 19 collapsed into the consolidated Step 17 completion tool by #1103). The numbering is preserved so external references (ADR text, policy rules, docs) don't need to track a moving target.

## Phase boundaries (control flow)

- **Phase A** (Steps 1 → 4.5), **Phase B** (Steps 5 → 6.6), **Phase C** (Steps 7 → 8.5), **Phase D** (Steps 9 → 11, then Step 17 with `phase="pre_merge"`), and **Phase E** (Steps 15 → 16 → Step 17 with `phase="post_merge"` → Step 20) run in fixed order. Phase D ends at a pre-merge **readiness** record and STOPS for the user to review and merge. **Phase E is the post-merge phase** (issue #963 extends #1058): the requirement `DRAFT→ACTIVE` transition (Step 15), traceability reconciliation (Step 16), the reconciled final report (Step 17 `post_merge`), and the issue close (Step 20) all run **only after the PR merges**, so Ground Control state never runs ahead of shipped code. See "Post-merge reconciliation ordering" and "Issue close mechanism" below.
- **Step 4 work-already-complete branch**: when Step 4's envelope returns `work_already_complete: true`, skip Steps 4.4 / 4.5 / 5 / 6 / 6.5 / 6.6 / 7 / 8 / 9 / 10 / 11 (there's no diff to push) and jump to Step 15 to reconcile Ground Control state. This branch has **no PR** - the code it documents already shipped under an earlier merge - so its Step 15/16 transition + reconcile run immediately (the post-merge gate is for *this* run's PR, and there is none); it records state via Step 4's completion comment rather than the merge-gated Step 17 `post_merge` report.
- **Step 10 CI failure**: on `ci_conclusion != "success"`, fix locally,
  return to Step 7 (re-stage), Step 8 (commit + push), Step 8.5
  (re-synchronize), then Step 10 again.
- **Step 11 SonarCloud findings**: same loop - fix, push, re-run Step 8.5,
  Step 10, then Step 11. Cap: 5 SonarCloud iterations.
- **Steps 6.5 / 6.6 escalated or capped**: record every remaining real finding as an open execution obligation and post a concrete decision request under the applicable documented pause class. Wait for the required decision without pushing; resume the same obligations afterward. The label set in Step 1 stays until the issue is closed at Phase E.
- **Post-merge reconciliation ordering (issue #963)**: the requirement status transition (Step 15), traceability reconciliation (Step 16), and the reconciled completion record (Step 17 `phase="post_merge"`) run in **Phase E, after the PR merges** - never pre-merge. Phase D's terminal signal is Step 17 with `phase="pre_merge"`: a **readiness** record (carrying a `ready_for_review` phase marker - *not* a `gc:final-report` marker - that does NOT run the traceability assertion, since the requirement is still DRAFT and links do not exist yet) that means "all automated gates are green; ready for the user to review and merge." `gc_assert_completion` with `phase="post_merge"` is **merge-gated** - it refuses with `completion_pr_not_merged` unless the linked PR is merged (`merged_at` non-null AND state `MERGED`) - then sequences the traceability-reconciliation assertion (`traceability_reconciled` marker) and `gc_post_final_report` in one deterministic call. This marker is enforced server-side; without it the post-merge final report refuses. The post-merge final report must reflect the reconciled Ground Control graph; the pre-merge readiness record must not claim a reconciliation that has not happened. Do not surface any earlier user-facing "complete" message - Phase D's readiness record is "ready to merge," not "done"; escalations before Phase E are because input is needed, not because the workflow is finished.
- **Plain-English outcome (issue #1156)**: both Step 17 invocations pass `plain_english_outcome` to `gc_assert_completion`. The tool renders it as the outcome section before the structured evidence, so both the pre-merge readiness record and the post-merge final report explain what the change means in product/operator terms without weakening CI, SonarCloud, or traceability gates.
- **Issue close mechanism (issue #1058 + #1156)**: the GitHub issue is closed at **Phase E (Step 20)** by `gc_close_issue_after_merge`, which verifies the linked PR is merged (`merged_at` non-null AND state `MERGED`) before running the close. The `Closes #<issue-number>` keyword in the PR body (rendered by `gc_render_pr_body` in Step 9) remains as the GitHub UI cross-link and may auto-close the issue at merge time; Step 20 is the idempotent backup that no-ops when the issue is already closed. **Because reconciliation now runs post-merge (issue #963), the issue may auto-close at merge BEFORE Phase E runs — that is expected and safe.** Phase E's transition (Step 15), reconciliation (Step 16), and final report (Step 17) operate on the requirement graph and the issue thread regardless of the issue's open/closed state (you can transition a requirement and comment on a closed issue), so the auto-close does not block Phase E; Step 20 then no-ops. The `in-progress` label is operational-only; its removal is optional best-effort and no longer a mandatory step gate (#1103). Closing from the agent without the merge-verification gate decouples the close from the merge - an unmerged or rolled-back PR would leave a closed issue with no shipped code behind it, and GitHub does not re-open on revert. Phase E is invoked by re-running `/implement <issue>` after the merge; Step 1's orchestrator detects the Phase-D-complete state - the `ready_for_review` marker present + a merged linked PR + post-merge reconciliation not yet recorded (no `gc:final-report` marker) - and short-circuits to **Step 15** (the start of Phase E: transition → reconcile → post-merge report → close), not back into Phase A–D. The detection keys on the reconciliation-marker state, NOT on the issue being open, precisely because the issue may have auto-closed at merge. If the PR exists but is not yet merged, Phase D is complete and the run STOPS awaiting the user's merge; it does not redo Phase A–D. `gc_close_issue_after_merge` performs only linked-PR resolution, merge-state verification, and the idempotent close (ADR-089); it does not list open issues, rank candidates, or return a next-issue recommendation.
- **Single human touchpoint**: PR merge (between Phase D and Phase E). The orchestrator never runs `gh pr merge`. Phase E runs autonomously when the user re-invokes `/implement` after the merge - there is no synchronous user gate inside Phase E.

## Per-step model routing (ADR-036)

Routing is opt-in per repo via `routing.enabled` in `.ground-control.yaml`
(default `false`) and advisory in every mode. The `tier` annotation on each
step file is the provider-neutral capability hint; the resolver maps it to a
model identifier for telemetry and future runtime selection, but it does not
choose an executor.

**Claude tier mapping** (canonical): `low` → `claude-haiku-4-5`, `medium` → `claude-sonnet-5`, `high` → `claude-opus-4-8`.

Every driver runs routine steps in the invocation session. A future runtime may
consume the same stage/tier hint without changing the workflow contract, but
Ground Control does not use it to force delegation.

**Cursor CLI** (issue #1189): invoke from the repo root in Agent chat:
`/implement <issue-number | requirement-uid>`; in CLI:
`agent "/implement <issue-number | requirement-uid>"`. Skill discovery uses
`bin/install-skills.sh`; Ground-Control repos also ship a project wrapper at
`.cursor/skills/implement/SKILL.md`.

## Telemetry (ADR-036)

When `telemetry.enabled` is true, the orchestrator calls `gc_log_step_telemetry` at the end of every routed step. The writer appends one JSONL line to `.gc/telemetry/<issue>-<sanitized-branch>.jsonl` (gitignored, repo-relative, containment-validated). `wall_time_ms` is mandatory and measured by the orchestrator around its dispatch. `input_tokens` / `output_tokens` are `null` when the harness does not surface them (Claude Code today). Telemetry is operational measurement only - it never gates any phase, never replaces the issue thread as the durable record, and never feeds back into the cycle-cap counter.
