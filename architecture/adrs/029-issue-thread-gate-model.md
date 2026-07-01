# ADR-029: Issue-Thread Gate Model

## Status

Accepted

## Date

2026-05-03

> **Style sync for issue #751 (2026-06-14):** Repository-wide Vale cleanup normalized punctuation in workflow prose. This ADR's issue-thread gate model stays the same.

> **Amended by issue #906 (2026-05-13):** Two changes to the review-loop contract this ADR establishes. (1) **Pre-push Codex review default cap drops from 3 to 1** (the cap value lives on the MCP tool as `CODEX_REVIEW_PREPUSH_HARD_CAP` and is now overrideable per-repo via `.ground-control.yaml::workflow.codex_review.pre_push_cap`, bounds `[1, 10]`); the `override_cap=true` + `override_reason=<authorization quote>` escape is unchanged and continues to grant a single over-cap cycle. Empirical rationale: PR #903 (a 4-cycle run) showed cycles 2 and 3 partly compounding the agent's own fix-introduced bugs rather than catching net-new bugs, and CI / SonarCloud / human review cover the residual risk. (2) **Test-quality review moves pre-push** to a new Step 6.6 in the same local-iteration band as the codex pre-push review (former Step 13 is merged out; former Step 14 collapses into Step 10's existing CI watch). Test-quality's default cap also drops to 1 with the same `workflow.test_quality_review.pre_push_cap` override path. The `gc:test-quality-review-cycle` marker family and the `gc_post_decision_record` contract are **unchanged**; what moves is the placement of the step in the workflow, not the durability mechanism. The PR now opens with both AI-assisted reviewers clean; CI + SonarCloud + human reviewer are the only post-push gates. SKILL.md Steps 13 / 14 are intentional tombstones; downstream Step 15 / 18 / 19 numbering is preserved so external references don't track a moving target. See `skills/implement/SKILL.md` Step 6.5 / 6.6 for the operative loop prose and `architecture/notes/quickfix-workflow-lane-preflight.md` for the preflight design context.

> **Amended by issue #937 (2026-05-21):** The pre-push review tools (`gc_codex_review` / `gc_test_quality_review` and their `_cycle` wrappers) and the architecture preflight may run as **async background jobs** (opt-in `async: true`), polled or cancelled via the new `gc_codex_job` tool. The issue-thread durable-record contract this ADR establishes is **unchanged**: every review cycle still posts its verbatim findings record and its `gc_post_decision_record` decision record to the resolved issue thread, and the cycle counter still anchors to the issue thread. Async only decouples the multi-minute child process from a single MCP tool-call so the client's tool-call timeout cannot abandon it and orphan the child (issue #893). See ADR-036 (amendments) for the job model and ADR-031 (amendments) for the codex-review framing.

> **Amended by issue #931 (2026-05-19):** The review-cycle payload is now a **verdict envelope**: `verdict` in (`ship`, `ship-with-fixes`, `don't-ship`) + non-empty `architectural_read` + per-finding `blocking[]` + capped `notes[]` (max 2). Block delimiter renamed from `===FINDINGS===` to `===REVIEW===`. One-off findings carry a required `sweep_evidence` field; class findings continue to carry `category.instances`. `gc_post_decision_record` accepts and renders the new shape with verdict + architectural_read rendered before blocking findings; the existing reviewer enum, defer-rejection, marker family, and wontfix authorization rules are unchanged. The principal-engineer recalibration motivation: the workflow now lets a clean review say `verdict: ship` as a first-class outcome instead of forcing reviewers to manufacture findings. See `skills/implement/SKILL.md` Step 6.5 / 6.6 for the operative loop prose and `architecture/notes/ai-review-recalibration-preflight.md` for the binding preflight guidance.

> **Amended by issue #1058 (2026-05-30):** Traceability reconciliation and post-merge issue close move from prose-only guidance to MCP-tool-enforced gates. (1) A new `gc_assert_traceability_reconciled` tool re-fetches each in-scope requirement and its links from the Ground Control REST API and posts a `traceability_reconciled` phase marker on success; the gate is enforced server-side rather than from the agent's claim. (2) `gc_post_final_report` now refuses unless that marker exists for the issue (same prerequisite shape as `gc_post_implementation_plan`'s `preflight` requirement), with the `/quickfix` lane explicitly exempt and a bounded `override_traceability_gate` + `override_traceability_reason` escape for user-authorized skips. (3) A new `gc_close_issue_after_merge` tool replaces ad-hoc `gh issue close` invocations as the canonical close path: it verifies `merged_at` non-null AND PR state `MERGED` before closing, and is idempotent on already-closed issues. The PR-body `Closes #<n>` keyword remains as the GitHub UI cross-link but is no longer load-bearing for the close. (4) A new Phase E (Step 20 in the orchestrator) drives the post-merge close after the user has merged. (5) A new `run_traceability_reconciliation_gate_contract` policy check in `tools/policy/checks.py` anchors the four prose surfaces (SKILL.md + step-17 + step-19 + step-20) so the contract documentation does not drift from the tool enforcement. The issue-thread durable-record contract this ADR establishes is **unchanged**: phase markers + final-report + decision records are still the durable surfaces; what changes is which gates are mechanically enforced versus prose-asserted. See `skills/implement/SKILL.md` Phase boundaries and `architecture/notes/traceability-post-merge-tool-gates-preflight.md` for the binding preflight guidance.

> **Amended by issue #1156 (2026-06-13):** Phase D closeout and Phase E continuation guidance become explicit tool-layer fields instead of agent-written prose. `gc_post_final_report` now requires `/implement` callers to pass `plain_english_outcome`, a bounded plain-language statement rendered as the final report's Outcome section before structured requirements, files, tests, reviews, CI, SonarCloud, traceability, and GRC evidence. The optional `summary` field remains available for extra context and must not carry the core user-facing outcome. After `gc_close_issue_after_merge` successfully verifies the linked PR merge and closes or observes an already-closed issue, it performs a best-effort lookup of open GitHub issues and returns `next_issue_recommendation` or an explicit no-recommendation/failure reason. Recommendation lookup is advisory and never weakens the merge-verified close gate. The `run_traceability_reconciliation_gate_contract` policy check now anchors the outcome and recommendation prose surfaces so the durable-record contract cannot silently drift.

## Context

ADR-021 ("Gated Agentic Development Loop") and the prior GC-O007 statement
required two human touchpoints per `/implement` run: **plan approval** and
**PR merge**. Plan approval was implemented as `EnterPlanMode` followed by
explicit user sign-off before TDD began.

In practice, plan approval became ceremony. Empirically, more than 95% of
plans were accepted as-is. The synchronous gate added coordination tax (the
human had to be available at the moment the plan was ready) without
materially affecting outcomes; divergences from the plan were caught by
review steps later in the loop, not by the plan-approval gate.

Pulsar already operated under a non-conformant model in which the plan was
posted as a GitHub issue comment and the workflow proceeded directly to TDD.
ADR-027 ("Agent-Neutral Implement Workflow Packaging") initially treated
this as a transport choice (`plan.approval_gate=issue-comment`), but the
ADR-027 author flagged that proceeding without an approval signal would
violate GC-O007's two-touchpoint contract. That conflict prompted this ADR.

## Decision

GC-O007's contract is amended to **one human touchpoint: PR merge**. The
GitHub issue thread becomes the durable record of plan, review findings,
and decisions on findings.

### Touchpoints

- **PR merge** is the only synchronous human gate. The user reviews the issue
  thread (plan + findings + decisions) and the PR diff, then merges.
- **No plan-approval gate.** The `/implement` skill posts the plan to the
  GitHub issue as a comment via `gh issue comment` and proceeds directly to
  TDD. No `EnterPlanMode` call. No synchronous user-approval wait.

### Issue thread as durable record

Every artifact that previously implied a human gate is now recorded as a
comment on the GitHub issue:

- **Plan**: posted as a comment when `/implement` enters Phase A. Includes
  context, approach, files-to-change, verification steps, risks.
- **Review findings**: every finding from codex review, refactor review,
  test-quality review, and SonarCloud is posted to its native location (PR
  review comment for codex; issue comment summary for review aggregates).
  The issue thread carries a summary linking back to the PR comments.
- **Decisions on findings**: for every finding, the agent records its
  disposition as an issue comment: **fix**, **wontfix**, or
  **not-applicable**, with a one-line rationale. `defer` is not a valid
  decision: the workflow's contract is "fix every finding before PR is
  ready." Recording the disposition is mandatory; agent silence on a
  finding is treated as a process violation.
- **Status transitions and traceability reconciliation**: happen
  asynchronously in Phase D (after reviews), not in a synchronous gate.

### Replaces / amends

- **ADR-021** is **amended**, not superseded. Its phase structure (A/B/C/D)
  and gate ordering are preserved. Only the human-touchpoint count changes
  from 2 → 1, and plan publishing moves from `EnterPlanMode` → `gh issue
  comment`.
- **ADR-027** drops its "transport not bypass" clause; plan publishing is
  uniformly the issue-comment transport, with no synchronous approval.
- The `plan.approval_gate` config knob proposed in #791 is NOT introduced;
  the gate model is uniform, not configurable per repo.

### Reviewer-of-record invariant (preserved)

ADR-027's invariant that codex remains the reviewer of record stays in
effect. Reviews route through `gc_codex_review`, `gc_codex_verify_finding`,
and `gc_codex_architecture_preflight` regardless of which agent runtime
drives the workflow.

### Pre-push review cycle state

Pre-push `gc_codex_review` runs with `uncommitted=true` are the canonical
Codex review step. The later post-push invocation is retained only as a
tool-layer defense-in-depth path for direct callers; the `/implement` skill
must not drive a second Codex review after the first push. Merge-commit drift
relative to the target branch is covered by CI, integration tests, and
SonarCloud, not by a duplicate Codex pass.

Because the workflow now has one Codex review step instead of two, the
`gc_codex_review` hard cap is **configurable per repo** (per the issue #906
amendment above). The MCP tool's module defaults are 1 cycle pre-push and
3 cycles post-push; the pre-push default may be overridden via
`workflow.codex_review.pre_push_cap` in `.ground-control.yaml`. The cap
remains anchored per issue (pre-push) and per PR (post-push); branch is
audit context, not part of the cap key.
`gc_codex_verify_finding` remains capped at two calls per
finding because verification loops are per-finding, not whole-review cycles.
Any older workflow text, issue prose, or ADR amendment that refers to a
five-cycle Codex cap, a hard two-cycle `gc_codex_review` cap, or two Codex
review steps is stale and must not drive implementation without a new ADR
amending GC-O007.

Because the pre-push review has no PR issue number, its durable cycle state is
anchored to the GitHub issue resolved at workflow Step 1. The marker records
the current branch name as audit-only context; the cap counter itself is
keyed by issue alone, so a branch rename on the same issue cannot reset the
counter. Earlier drafts of this ADR had the cap keyed by `(issue, branch)`,
but PR #800 review (cycle 2) flagged that as a bypass: a noncompliant agent
could rename `<issue>-x` to `<issue>-x-2` and start fresh. Per-issue keying
closes that path. Legitimate "abandon and restart on a new branch" remains
available via the user-authorized `override_cap=true` + `override_reason`
path; the override marker stays distinguishable from regular cycle markers in
the audit trail.

The configured cap (default 1 per #906; per-repo override via
`workflow.codex_review.pre_push_cap`, bounds `[1, 10]`) is hard against agent
self-authorization: the agent cannot run cycle cap+1 to "verify the fix" of
the previous cycle's findings. Last-in-cap findings must be fixed in place;
if concern remains after fixing them, the agent posts an issue-thread comment
summarizing the remaining concern and fix history and escalates to the user.
The user may then authorize cycle cap+1 explicitly via `override_cap=true`
+ an `override_reason` quoting that authorization, or decide a different
workflow move (stop, re-scope, open a fresh issue). The marker preserves the
distinction so the audit trail records whether each cycle ran in-cap or under
user-authorized override.

The marker belongs to the same issue-thread marker family as plan, phase,
review-cycle, and verify-cycle markers. Implementations must reuse the
existing issue-comment read/post helpers, marker parser/evaluator pattern, and
structured refusal result style; they must not add a local state file, git
notes, database row, Temporal state, or driver-local counter for this cap.

### Tool-layer enforcement boundary

The MCP server is the enforcement boundary for workflow ordering, cycle caps,
GitHub posting, and durable markers. Implementation work for issue #794 must
extend the existing MCP review/phase machinery rather than introducing a
parallel workflow state model.

Reuse these existing cross-cutting patterns:

- the `ensureGitRepo` and `getOwnerRepo` repository resolution path before any
  GitHub or git side effect;
- the issue-comment marker family and paginated issue-comment reader for
  durable state, including marker-shaped-text escaping so reviewer output
  cannot poison counters;
- pure parser/evaluator helpers for marker counting and prerequisite decisions,
  with tests covering malformed markers, wrong issue/PR ids, and cap
  boundaries;
- structured refusal envelopes with stable `error`, `message`, `prior_cycles`
  or `missing`, `cap`, and `next_action` fields instead of thrown control-flow
  exceptions for expected gate failures;
- the host-side GitHub posting boundary, sensitive-content guardrail, and
  partial-failure envelopes already used by `gc_codex_review`;
- `.ground-control.yaml` resolution through `gc_get_repo_ground_control_context`
  when workflow behavior needs repo configuration.

Do not duplicate the workflow contract in skill-only prose, local files, git
notes, in-memory counters, ad hoc JSON blobs, Temporal state, or a new database
table for this bridge implementation. Do not create separate schemas for
pre-push and post-push review cycles unless their persisted marker identity
actually differs. Do not make branch name, PR number, or commit lineage part of
the pre-push cap key; those are audit context or post-push direct-caller
defense-in-depth context, not reset levers for the canonical Step 6.5 cap.

### Test-quality review uses the same decision-record contract

The test-quality review step (Step 6.6 per the #906 amendment; formerly
Step 13) via the `gc_test_quality_review` MCP tool records every cycle on
the issue thread using the same `gc_post_decision_record` surface as
Step 6.5's codex review, with `reviewer: "test-quality"` and the findings
list.

The reviewer was originally a Skill (`review-tests`) but issue #884 v2
moved it to an MCP tool to fix a behavioral regression: the Skill-tool
boundary produced prose-formatted findings, and the autoregressive
parent agent kept echoing them back to the user as a status report
instead of fixing them in the same turn. The SKILL.md "do not echo,
fix in same turn" prose could not override the tool-boundary bias.
The `gc_test_quality_review` MCP tool returns a structured envelope
with a `next_action` field; the parent reads it as a directive, not
as text to summarize. See
`architecture/notes/test-quality-review-engine.md` for the full
mechanism (claude CLI exec wrapper, OAuth vs `ANTHROPIC_API_KEY`
auth, cycle cap markers, findings record, failure modes). A clean cycle posts `findings: []`, which renders as
`**Findings:** 0 (clean run)`. The successfully posted record is the
structured durable signal that the cycle is complete and the workflow
advances. "Successfully posted" is dispositive: the parent advances
to Phase C (stage / commit / push) only after `gc_post_decision_record`
returns `ok: true` with a posted comment id/url. On `ok: false`, the parent
follows the returned `error` / `next_action` envelope (sensitive-content
rejection, body-size cap, `gh` posting failure, network), fixes the
underlying issue, and retries the post; it does NOT enter Phase C with
the durable marker missing. Treating the attempted call as the signal would
re-open the #884 silent-advance failure mode in a different shape. There is
no separate marker family for test-quality cycles, and there is no human
acknowledgment turn between the test-quality clean signal and Phase C;
the parent `/implement` workflow consumes the successful clean record and
proceeds in the same turn. (Per the #906 amendment, the former Step 13 /
Step 14 post-PR phase merged into Step 6.6 pre-push + Step 10 CI watch;
the contract above is unchanged in substance; the "advance" target just
shifted from Step 14 to Phase C entry.) Issue #884 was the original regression: when the SKILL prose
treated the `review-tests` skill's human-readable "no issues found" line
as the only signal, the parent agent stopped at the skill-return boundary
instead of advancing. The skill's prose line remains for transcript
readability; the decision-record marker on the issue thread is the
workflow contract.

The cycle cap for test-quality defaults to 1 per issue (per the #906
amendment above), aligned with the codex pre-push cap default. It is
configurable per repo via `workflow.test_quality_review.pre_push_cap`
in `.ground-control.yaml`. Per #884 v2 the cap is **server-side**: the MCP tool
`gc_test_quality_review` counts `gc:test-quality-review-cycle` markers
on the issue thread and refuses cycle 4 unless `override_cap=true` with
a non-empty `override_reason`. The marker family is disjoint from
`gc:codex-prepush-cycle` and `gc:decision-record`; the three counters
never cross-count. Branch is recorded for audit context only; a
branch rename on the same issue does NOT reset the counter.

The whole point of the test-quality review is to **fix** the tests, not
to file a status report on them. The MCP tool returns a structured
envelope with `findings[]` and `next_action`; the parent /implement
agent reads `next_action` as a directive. On
`next_action: "fix_findings_and_reinvoke"` the parent fixes every
finding in the same agent turn (classify, apply the fix, self-verify,
commit/push, post the decision record with `fix` / `wontfix` /
`not-applicable` dispositions, re-invoke). On
`next_action: "post_clean_decision_record_and_advance_to_phase_c"` the
parent posts the clean `gc_post_decision_record(findings: [])`,
confirms `ok: true`, and proceeds to Phase C (stage / commit / push) in
the same turn. (The string changed from `..._advance_to_step_14` to
`..._advance_to_phase_c` when issue #906 moved Step 13's test-quality
review pre-push to Step 6.6; Step 14 no longer exists.) The
parent does NOT echo findings back to the user; the v1 prose-only
attempt to forbid that behavior failed because the Skill-tool
boundary's autoregressive bias overrode the SKILL.md rule; the v2 MCP
tool boundary closes that bias by returning structured `next_action`
rather than prose findings.

### Codex findings issue-thread record

After every successful `gc_codex_review` cycle, the MCP server must post a
human-readable findings record to the resolved GitHub issue thread. That
comment is the durable record for "what Codex said in cycle N"; agent-written
decision summaries remain a separate record of what the agent did with each
finding.

The findings record must preserve the existing machine-readable cycle marker
contract and include:

- cycle number, cap, reviewer names, and review mode (`pre-push` or
  `post-push`);
- the verbatim `core_review_text` and `security_review_text` returned by the
  reviewers;
- for post-push reviews, every inline PR review comment URL that was created.

Post-push inline PR review comments still exist for anchored human review.
The issue-thread findings comment is additive, not a replacement for inline
comments. If posting the issue-thread findings record fails, the review run is
not durable and must fail fast with a structured
`review_comment_post_failed` result while preserving the review text and
finding metadata in the returned payload.

Implementations must route this through the existing host-side GitHub posting
boundary in `gc_codex_review`: use the same `gh api` issue-comment helpers,
secret-content guardrails, parser/evaluator result envelopes, and
issue-resolution logic already used for phase markers, cycle markers, and
inline PR comment posting. Do not let Codex call `gh` directly, do not create a
second GitHub client abstraction, and do not make agent prose the only source
of truth for review findings.

The cap mechanism is an audit / discipline gate, not a security boundary. A
fully noncompliant or compromised agent with shell access has many paths to
bypass; the cap narrows the most likely accidental-bypass path (branch
rename) but does not protect against all attacks. The user's PR merge
remains the only synchronous human gate.

### `defer` is not a valid disposition

The "Decisions on findings" bullet above states the contract in one sentence:
`defer` is not a valid decision. Issue #830 documented that agents kept
inventing a third path anyway: `out of scope for this PR; follow-up issue to
track it`, `will be addressed in a subsequent PR`, `deferred to a later
iteration`, or simply writing `deferred`/`TBD` in a closing comment without
filing anything. Once the issue closes, the deferred item has no anchor: not
in the requirement graph, not in any tracker, not on any backlog. It is
silent debt. This subsection makes the prohibition explicit and names its
mechanical enforcement.

**The only valid dispositions for a reviewer finding are:**

1. **`fix`**: the finding is fixed now, in the same diff. For a *class*
   finding (one instance of a pattern that recurs), the fix is designed at
   the category level (a structural gate, a shared helper, a parameterization,
   a single point of repair) and applied to every instance at once, not
   whack-a-mole to the reviewer-named site only. Fixing a `class` finding on
   the named site alone is a process violation in the same shape as silent
   deferral: it leaves the category un-addressed and burns a review cycle the
   cap is not meant to absorb.
2. **`wontfix`**: the finding is genuinely wrong, dangerous to fix in
   context, or a false positive. Requires **explicit user authorization** on
   the issue thread, quoted in the disposition comment.
3. **`not-applicable`**: the finding does not actually apply (false positive
   on this codebase, out of the diff's real scope, etc.), recorded with a
   rationale.

**Deferral language is forbidden.** Phrases such as `Defer this to a follow-up PR / issue /
later iteration / subsequent commit`, `will be addressed in a follow-up`,
`fixed in a subsequent PR`, `handled as a follow-up issue`, and, in a
comment that closes or reports completion on the issue under implementation,
a bare `deferred`, `TBD later`, or `to be done later/separately` are all
deferral dispositions. Filing a tracking issue does **not** convert a deferral
into a valid disposition; the contract is fix-or-escalate, not fix-or-file.
A new issue's own body legitimately scope-bounds future work (an
`## Out of scope` section, a "this builds on #N" note); that is scope
*definition*, not finding *deferral*. The distinction is by phrase, not by
section heading.

**Mechanical enforcement: two defense-in-depth layers over the same
contract, neither replacing the other:**

- **Tool-call time**: the PreToolUse hook `.claude/hooks/block-defer-language.py`
  (installed via `scripts/bootstrap-claude-workflow.sh`'s `WORKFLOW_HOOKS`
  allowlist, registered in `~/.claude/settings.json`'s `PreToolUse[Bash]`
  chain) inspects `gh issue {create,edit,comment,close}` and
  `gh pr {create,edit,comment}` body/title text (including heredoc bodies)
  and blocks the call (exit 2) on deferral-disposition language, routing the
  agent back to fix-or-escalate.
- **Completion gate**: `bin/policy` (`tools/policy/checks.py`'s
  `run_no_deferral_disposition_check`) scans the resolved PR body for the same
  Tier-1 deferral phrases at completion-gate / CI time.

Both layers share one classifier; `tools/policy/deferral_cases.json` is the
golden-case file both test suites load, so the hook's standalone copy and the
policy copy cannot drift without a test failing. The classifier's allowed
contexts are encoded in those cases, not in agent prose; future tuning is
reviewable.

**Text scanning is necessary, not sufficient.** A scanner cannot prove an
agent *silently dropped* a finding it never wrote about. That failure mode
(agent silence on a finding is a process violation, per the bullet above)
is caught only by reconciling the issue-thread Codex findings record (every
cycle's verbatim finding list) against the agent's disposition comments
(one `fix`/`wontfix`/`not-applicable` rationale per finding). The hook and
policy layers catch *written* deferral language; the findings-vs-decisions
reconciliation is the existing control for *unwritten* omission and is
unchanged by this amendment.

## Consequences

### Positive

- One synchronous gate instead of two: the workflow proceeds asynchronously
  after `/implement` is invoked. The user sees the result on PR ready-to-merge
  rather than being interrupted mid-run.
- Issue thread becomes a single durable surface that survives PR merge/close,
  unlike PR review comments which are tied to the PR's lifecycle.
- Plan, findings, and decisions are colocated and time-ordered, making
  retrospective audit easier than scraping multiple surfaces.
- Aligns the four current repos on a single gate model; pulsar's prior
  divergence becomes the new norm rather than a fork.

### Negative

- Removing the synchronous plan-approval gate transfers accountability for
  early stage course correction onto the codex preflight + plan content
  itself. If preflight is weak or the plan is wrong, the workflow proceeds
  to TDD against a flawed plan, and the cost of rework is higher than
  catching it at plan-approval time.
- Issue threads become longer. Plans + finding summaries + decisions can
  accumulate to hundreds of lines per `/implement` run.

### Risks

- **Agent silence on finding decisions.** Without a human approval gate,
  agents could silently mark findings wontfix without rationale.
  Mitigation: every finding decision is mandated as an issue comment with
  rationale; the codex `verify_finding` flow already enforces this on PR
  comments. Issue-thread duplication is the new explicit step.
- **Drift in plan quality.** Plans that previously got rubber-stamped will
  now drive TDD without that rubber-stamp gate. If plan quality erodes,
  it'll show up in larger review-fix loops. Counter: the codex preflight
  + plan-rules-from-`.ground-control.yaml` already shape plans before TDD;
  this is the same input quality, just without a synchronous human pause.
- **Audit retrospective.** When a PR ships with bad code, the question
  "did the human approve this?" no longer has a yes/no. Counter: the issue
  thread is the durable record; reviewers can trace what was known and
  what was decided.

## Migration

- **This PR (#791)** lands ADR-029 simultaneously with the canonical
  `skills/implement/SKILL.md` rewrite that follows the new gate model.
- **In-flight work** under the old gate model finishes under the old gate.
  This PR itself is being executed under the deprecated plan-approval gate
  (which the user explicitly granted before this ADR was authored). Future
  `/implement` runs use the new gate.
- **GC-O007 statement** is amended via `gc_update_requirement` in this PR
  to reflect one human touchpoint and the issue-thread durable-record
  model. The prior statement remains in `gc_get_requirement_history` for
  audit.

## Non-Goals

- Eliminating PR review or merge approval. PR merge stays a synchronous
  human gate.
- Making the workflow fully autonomous. The user still owns merge and
  remains accountable for ratification of the work.
- Re-implementing `EnterPlanMode` as a Codex feature. Codex-driven
  `/implement` runs use the same `gh issue comment` plan transport as
  Claude-driven runs.

## Related Requirements

- GC-O007 Gated Agentic Development Loop (statement amended)
- GC-O009 Workflow Orchestration via Temporal (the durable end state)

## Related ADRs

- ADR-021 Gated Agentic Development Loop (amended)
- ADR-027 Agent-Neutral Implement Workflow Packaging (companion ADR; same PR)
- ADR-028 Temporal Workflow Orchestration Boundary (forward-looking for
  GC-O009)

**Amendment: renderer summary byte caps (#964).** `gc_render_pr_body` and `gc_post_final_report` now enforce reject-not-truncate byte caps on their caller-controlled summary fields (PR-body `summary`, final-report `summary`, final-report `plain_english_outcome`, and final-report `reviews[].summary`). `gc_post_decision_record`'s schema is unchanged. The canonical succinctness rule lives in `skills/implement/steps/_review-loop-rules.md § Update succinctness (canonical)` and applies to every issue-thread durable record. `buildFinalReport` no longer emits placeholder sections for empty requirements or reviews.

**Amendment: issue close mechanism (#862 typed-action-items PR).** The /implement Step 18 no longer runs `gh issue close`. The GitHub issue closes via `Closes #<issue-number>` in the PR body (rendered by `gc_render_pr_body` in Step 9) when the user merges the PR. Step 18 only removes the `in-progress` label set in Step 1. Closing from the agent decoupled the close event from the merge: an unmerged or rolled-back PR would leave a closed issue with no shipped code (GitHub does not re-open issues on revert). Step 19 (final report) is correspondingly tightened: traceability reconciliation (Steps 15 through 17) is an explicit precondition, and no earlier step surfaces a user-facing "complete" signal (prior escalations are for input, not for "done"). The /quickfix sibling lane is updated in lockstep.

**2026-05-26 (issue #989).** The new `/integrate` lane (GC-O011) is repo-scoped, not issue-scoped: its plan and readiness records surface through the invoking interface (terminal output), not as comments on a GitHub issue thread. The single-merge-touchpoint contract is preserved unchanged: the lane prepares PRs but does not merge them. Consultation halts (clause (h) of GC-O011) consult the maintainer through the invoking interface and do not post to any GitHub issue. ADR-029's "issue thread is the durable record" guarantee applies to issue-anchored runs (`/implement`, `/quickfix`); the `/integrate` lane's records are operational and live on the maintainer's terminal, in the MCP tool's return envelope, and in the local halt ledger at `<repo>/.gc/integration-runs/<run-id>/halt.json`.

**2026-05-26 (issue #989 merge carve-out).** The single-human-touchpoint contract is amended to permit `gc_integration_manager` action=prepare mode=merge to execute the merge for queue entries that the same lane has just prepared (rebased, completion-gate green, CI green, Sonar green). The carve-out is narrow: merge is only legal when invoked through the integration manager's MCP tool boundary, only on PRs the same run has marked outcome=ready, and only when the repository has opted in via `workflow.integration_manager.merge_strategy`. All other agent paths to merge remain forbidden by skill prose and by the `.claude/hooks/git-merge-guard.py` PreToolUse hook that already blocks `gh pr merge` and `git merge` from agent Bash invocations. The MCP server itself is the only privileged-side-effect surface that can execute the merge; the hook layer does not apply to MCP server subprocesses, so the access-control surface is the gc_integration_manager tool registration.

**2026-06-10 (issue #1099 threat/risk screening gate).** A new Phase A gate, Step 3.5 (GRC screening), runs between codebase assessment (Step 3) and planning (Step 4). The step classifies the planned change surface against the project's existing threat-model and risk-scenario workspaces and posts a durable screening record on the GitHub issue thread via the new `gc_post_grc_screening` MCP tool. The issue-thread durable-record contract is unchanged: Phase A now includes the screening record as a new artifact on the issue thread, placed before the plan comment. The single-human-touchpoint contract (PR merge) is unchanged. See ADR-057 for the full decision and `skills/implement/steps/step-03.5-grc-screening.md` for the operative prose.

**2026-06-14 (issue #1103 Phase D consolidation).** The issue-thread durable-record contract is updated: Phase D now posts at most three records: the `traceability_reconciled` marker (posted by `gc_assert_traceability_reconciled` within `gc_assert_completion` at Step 17), the `grc_reconciled` marker (posted by `gc_assert_grc_reconciled` within `gc_assert_completion` at Step 17), and the final report (posted by `gc_post_final_report` within `gc_assert_completion` at Step 17). The former separate Steps 18 (label removal) and 19 (final report) are collapsed into Step 17 via the composite `gc_assert_completion` tool. The single-human-touchpoint contract is unchanged.

**2026-06-18 (issue #1181 model-tier refresh).** The `/implement` high-tier capability model id was bumped from `claude-opus-4-7` to `claude-opus-4-8` in the `CLAUDE_MODEL_BY_TIER.high` default map (`mcp/ground-control/lib.js`) and the high-tier `.ground-control.yaml` routing stages (`planning`, `review_cycle_1_consume`). This changes only which Claude model runs the parent-only high-tier steps; the issue-thread durable-record contract, the gate model, and the single-human-touchpoint contract defined by this ADR are unchanged.

**2026-06-19 (issue #1189 Cursor CLI driver).** Cursor CLI may drive issue-anchored `/implement` runs using the same issue-thread durable-record contract. The driver invokes the canonical skill via host install (`bin/install-skills.sh` → `~/.cursor/skills/implement/`) or the project wrapper at `.cursor/skills/implement/SKILL.md`; plan, findings, decision records, phase markers, and final reports still post to the GitHub issue thread via MCP. The single-human-touchpoint contract (PR merge) is unchanged.

**2026-06-22 (issue #963 post-merge reconciliation ordering).** The requirement `DRAFT→ACTIVE` transition (Step 15), traceability reconciliation (Step 16), and the reconciled final report (Step 17) move from **Phase D (pre-merge) to Phase E (post-merge)**, extending the #1058 close-ordering guarantee to the rest of the Ground Control state: a reviewed-but-abandoned PR no longer leaves a requirement flipped ACTIVE with links to code that never shipped. The "Status transitions and traceability reconciliation: happen asynchronously in Phase D (after reviews)" line in the Decision above is superseded by this amendment - they now happen in Phase E, after merge. Mechanically, `gc_assert_completion` gains a `phase` parameter: `phase="post_merge"` (default) is **merge-gated** (refuses with `completion_pr_not_merged` unless the linked PR is merged, mirroring `gc_close_issue_after_merge`) and runs the traceability + GRC assertions plus the final report; `phase="pre_merge"` is the new Phase D terminal - it posts a readiness record carrying a `ready_for_review` phase marker (not a `gc:final-report` marker), asserts the Step 3.5 GRC screening record exists (a Phase A fact, so a missing screening blocks readiness rather than surfacing only post-merge) but NOT traceability (the requirement is still DRAFT pre-merge), and still enforces every input gate (CI green, Sonar pass/legit-skip, codex review present, sensitive/reserved/defer scrubs). Phase E is re-entered by re-running `/implement <issue>` after merge; Step 1 keys off the `ready_for_review` marker + a merged linked PR + the absence of a `gc:final-report` marker, and short-circuits to Step 15. Detection deliberately does NOT require the issue to be open: because reconciliation now runs post-merge, the PR body's `Closes #<n>` keyword may have auto-closed the issue at merge before Phase E runs; the transition, reconciliation, and final report all operate on the requirement graph and issue thread regardless of issue state, and the Step 20 close then no-ops (`already_closed: true`). The issue-thread durable-record contract and the single-human-touchpoint contract (PR merge) are unchanged; only *which side of the merge* the transition/reconcile/final-report records land on changes. GC-O007 statement (D) is amended in lockstep (transition/reconcile are post-merge; the PR is presented for merge at the end of Phase D).

**2026-06-28 (issue #1245 review-cap disposition marker).** A new durable marker family `gc:review-auto-disposition` (schema `gc.implement.review-auto-disposition/v1`) records the automated over-cap review disposition on the issue thread, preserving this ADR's issue-thread-as-durable-record contract. The new `gc_review_cap_disposition` MCP tool posts it through the same hardened path as every other workflow record (reserved-`gc:`-prefix rejection, `detectSensitiveBodyContent`, GitHub body-size cap); a failed post authorizes nothing. Crucially, auto-grant authority for an over-cap cycle is the **marker**, not agent text: `gc_codex_review_cycle` / `gc_test_quality_review_cycle` verify a valid `one_more_cycle` disposition for the same issue+reviewer before honoring a new `auto_grant=true` parameter, so an agent cannot self-authorize past the cap by passing arbitrary `override_reason`. Because an issue thread is writable by anyone who can comment, the marker is treated as a forgeable audit record until four conditions hold (enforced in the pure `evaluateAutoDispositionGrant` that `verifyAutoDispositionGrant` delegates to): (1) **provenance**: only a grant marker posted by the trusted MCP identity (the gh-authenticated login that posts every durable record) counts; a marker authored by any other commenter is ignored, and an unresolvable trusted login denies authorization rather than trusting raw text; (2) **authoritative mode**: a `mode: shadow` marker is record-only and never authorizes, so a shadow disposition cannot be promoted into a grant later; (3) **cap-boundary binding plus single use**: the cap boundary is derived server-side (the effective reviewer cap from config, the over-cap count from durable cycle markers, never a caller-supplied `cycle`/`cap`), the grant records that boundary, and once the granted over-cap cycle's own durable cycle marker lands on the thread the grant is spent (it cannot be replayed to run a later cycle); (4) the `max_auto_overrides` ceiling. The disposition tool also refuses to mint a grant before the cap boundary is reached (`disposition_before_cap_boundary`), and in `mode: shadow` it clamps the returned `next_action` to escalation so a consumer can never advance on a shadow run. Grants are scoped per issue+reviewer; branch stays audit-only. This is the tool-layer-enforcement-boundary principle applied to the over-cap decision: it moves from prose/agent to a marker-gated MCP tool. The feature is config-gated by `workflow.review_disposition.enabled` (default false; disabled → no marker, no behavior change). `mode: shadow|authoritative` (default shadow) is honored by the orchestrator, not the tool: in shadow the disposition is posted but the run still escalates to the user, building agreement data before the gate acts authoritatively. The single-human-touchpoint contract (PR merge) and the reviewer-of-record invariant are unchanged.

**2026-07-01 (issue #1264 Sonnet-tier refresh).** The `medium`-tier routing-default model id and the `gc_test_quality_review` engine default were bumped from `claude-sonnet-4-6` to `claude-sonnet-5`, and the executable-routing model-id validator was loosened to accept single-segment canonical ids. This is a routing-config value/validation change with no effect on this ADR's issue-thread-as-durable-record contract: no marker family, durable-record surface, hardened-post path, or single-human-touchpoint invariant changes.
