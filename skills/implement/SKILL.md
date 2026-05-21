---
name: implement
description: End-to-end issue implementation — from plan through merged PR. Agent-neutral (Claude Code, Codex). Parameterized by .ground-control.yaml. Thin orchestrator that delegates per-step work to subagents per ADR-036 + issue #934.
argument-hint: <issue-number | requirement-uid>
disable-model-invocation: true
---

# Implement (orchestrator): $ARGUMENTS

This skill is the canonical, agent-neutral implementation of the Ground Control `/implement` workflow. It runs from either Claude Code or Codex against the same content, with repo-specific values supplied by `gc_get_repo_ground_control_context` (per ADR-027).

The workflow handles the entire lifecycle: plan, implement, verify, commit, push, PR, CI, reviews, fix, requirement transitions, traceability reconciliation. **The user's only synchronous touchpoint is PR merge** (per ADR-029). Plans, review findings, and decisions on findings are recorded as comments on the GitHub issue thread so the durable record survives PR merge/close.

`$ARGUMENTS` may be either a GitHub issue number OR a Ground Control requirement UID; in the UID case Step 1 finds or creates the matching issue and runs against it. Bug fixes, refactors, dependency updates, and other requirement-free work enter the same workflow via an issue with zero requirements in scope.

**Templating convention.** Where the prose below references a path or value with `{cfg.X|default Y}`, the agent reads `cfg` from `gc_get_repo_ground_control_context`'s response (Step 1) and substitutes `cfg.X` if non-null, else `Y`.

---

## Step contract (issue #934)

This SKILL is a thin orchestrator. The 716-line monolithic prose that used to live here now lives one file per step under `skills/implement/steps/step-NN-<id>.md`. The canonical review-loop rules live at `skills/implement/steps/_review-loop-rules.md` (Step 6.5 and Step 6.6 reference it; do not restate elsewhere).

**For each step in the list below**, the orchestrator does the following:

1. Resolve the route through the `gc_resolve_workflow_route` MCP tool using the stage id (left column of the table). The resolver reads `.ground-control.yaml` and returns `{provider, agent, model, tier, fallback_policy}`. If routing is disabled or unavailable, follow the returned fallback policy.
2. **If `agent: subagent`** — spawn an `Agent` (or driver-equivalent) subagent with the resolved model. The subagent's prompt is verbatim: *"Execute `skills/implement/steps/step-NN-<id>.md` against issue {issue_number}. Cached state from prior steps: `{cached_state_json}`. Return a single short envelope `{status, cached_for_next_step}` and nothing else."* Await the envelope.
3. **If `agent: parent`** — read the step file inline and execute. The parent runs the step locally.
4. **Telemetry**: when `cfg.telemetry.enabled` is true, call `gc_log_step_telemetry` at the end of the step with `{step, tier, model, wall_time_ms, outcome, input_tokens: null, output_tokens: null}`. `wall_time_ms` is measured around the dispatch.
5. Merge the returned `cached_for_next_step` fields into the running state passed to the next step.

The parent never sees verbatim subagent prose, raw `gh`/`git` output, full file contents, raw CI logs, raw Sonar payloads, or per-finding review bodies — those stay in the subagent's context or server-side in the MCP tool layer.

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
| 9 | `pr_body` | `steps/step-09-pr-body.md` |
| 10 | `ci_monitor` | `steps/step-10-ci-monitor.md` |
| 11 | `sonarcloud` | `steps/step-11-sonarcloud.md` |
| 15 | `transition_reconcile` | `steps/step-15-transition.md` |
| 16 | `transition_reconcile` | `steps/step-16-reconcile.md` |
| 17 | `transition_reconcile` | `steps/step-17-verify.md` |
| 18 | `close_issue` | `steps/step-18-close-issue.md` |
| 19 | `final_report` | `steps/step-19-final-report.md` |

Steps 12, 13, 14 are intentional tombstones (post-push Codex review collapsed into pre-push Step 6.5 by #804; post-PR test-quality review moved pre-push to Step 6.6 by #906; final CI re-verify collapsed into Step 10's existing CI watch). The numbering is preserved so external references (ADR text, policy rules, docs) don't need to track a moving target.

## Phase boundaries (control flow)

- **Phase A** (Steps 1 → 4.5) and **Phase B** (Steps 5 → 6.6) and **Phase C** (Steps 7 → 8) and **Phase D** (Steps 9 → 19) run in fixed order.
- **Step 4 work-already-complete branch**: when Step 4's envelope returns `work_already_complete: true`, skip Steps 4.4 / 4.5 / 5 / 6 / 6.5 / 6.6 / 7 / 8 / 9 / 10 / 11 (there's no diff to push) and jump to Step 15 to reconcile Ground Control state.
- **Step 10 CI failure**: on `ci_conclusion != "success"`, fix locally, return to Step 7 (re-stage), Step 8 (commit + push), then Step 10 again.
- **Step 11 SonarCloud findings**: same loop — fix, push, re-run Step 10, then Step 11. Cap: 5 SonarCloud iterations.
- **Steps 6.5 / 6.6 escalated or capped**: STOP and wait for the user. The label set in Step 1 stays until Step 18 clears it. Do not push commits while waiting.
- **Single human touchpoint**: PR merge (after Step 19). The orchestrator never runs `gh pr merge`.

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

**Claude tier mapping** (canonical): `low` → `claude-haiku-4-5`, `medium` → `claude-sonnet-4-6`, `high` → `claude-opus-4-7` (the parent — no subagent spawn).

**Codex** (and other drivers without subagent-with-model support): ignore the tier annotation and run every step on the session model. The contract is forward-compatible — a future router consumes the same step-id + tier hints without changing this SKILL.

## Telemetry (ADR-036)

When `telemetry.enabled` is true, the orchestrator calls `gc_log_step_telemetry` at the end of every routed step. The writer appends one JSONL line to `.gc/telemetry/<issue>-<sanitized-branch>.jsonl` (gitignored, repo-relative, containment-validated). `wall_time_ms` is mandatory and measured by the orchestrator around its dispatch. `input_tokens` / `output_tokens` are `null` when the harness does not surface them (Claude Code today). Telemetry is operational measurement only — it never gates any phase, never replaces the issue thread as the durable record, and never feeds back into the cycle-cap counter.
