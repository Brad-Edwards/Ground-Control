---
name: quickfix
description: "Lower-ceremony lane for straightforward, lower-risk fixes (drops preflight/plan/AI-reviews ceremony; keeps every mechanical guardrail). AI-assisted reviews opt-in via --review. Issue numbers only (UID input is invalid; use /implement <uid>). Sibling to /implement; mid-flight upgradeable."
argument-hint: "[--review] <issue-number>"
disable-model-invocation: true
---

# Quickfix: Lower-Ceremony Workflow Lane

Canonical, agent-neutral implementation of the Ground Control `/quickfix` workflow. A purpose-built fast lane for **straightforward, lower-risk fixes** that don't warrant the full `/implement` ceremony (preflight, plan post, AI-assisted reviews, final-report tool, requirement transitions). Drops the ceremony designed for requirement-driven multi-clause work; keeps every mechanical guardrail the repo enforces.

**Sibling to `skills/implement/SKILL.md`.** This skill cross-references the canonical full workflow at every step rather than duplicating prose—the contract surfaces (branch shape, in-progress signal, changelog fragment, PR-title rules, `gc_render_pr_body`, CI/SonarCloud, no-deferral, user-owns-merge) are identical. The only differences are the dropped ceremony.

## When to pick `/quickfix` vs `/implement`

Judgment call, gating heuristic:

- **`/quickfix`** when the fix is obvious from the issue description, touches **< ~10 files**, has **no architectural footprint**, and the agent has no open design questions. Examples: parser bug, doc typo, SonarCloud finding cleanup, dependency bump, lint fix, narrow refactor with no behavior change, "the reviewer told me exactly what to do" follow-up.
- **`/implement`** when the issue carries a `## Requirements` section (UIDs in scope), or the diff is wider than ~10 files, or the design is unsettled, or there's any cross-aggregate blast radius. Anything that benefits from a codex production-readiness pass + test-quality review.

The user picks the lane explicitly at invocation time. The issue is the durable anchor—a `/quickfix` run can be upgraded to `/implement` mid-flight by re-invoking `/implement <same-issue>`.

## Per-step model routing (ADR-036)

Routes through the same `gc_resolve_workflow_route` resolver as `/implement` (see ADR-036 + `skills/implement/SKILL.md` § "Per-step model routing"). Stages reused: `issue_branch_resolution`, `codebase_assessment`, `implementation`, `precommit`, `completion_gate`, `review_cycle_1_consume` (only when `--review`), `review_fix_application`, `git_publish`, `pr_body`, `ci_monitor`, `sonarcloud`, `test_quality_review` (only when `--review`), `close_issue`. Stages NOT used (because the skill drops them): `architecture_preflight`, `planning`, `clause_mapping`, `transition_reconcile`, `final_report`. Routing and telemetry are opt-in per repo via `.ground-control.yaml` (same `cfg.routing.enabled` and `cfg.telemetry.enabled` knobs).

## Invocation

```
/quickfix <issue-number>           # default: AI-assisted reviews off
/quickfix --review <issue-number>  # opt-in: codex pre-push + test-quality pre-push, cap 1 each
```

The `<issue-number>` argument is a plain GitHub issue number, a `#`-prefixed integer, or `issue:N`. **Requirement UIDs are NOT a valid `/quickfix` input.** `/quickfix` runs are requirement-free by definition, so accepting a UID would be a lane-mismatch that quietly drops the requirement lifecycle. If the user passes a UID, STOP and tell them to use `/implement <uid>` instead.

### Hard precondition: no requirements in scope

After resolving the issue, **fetch its body and reject if it carries a `## Requirements` section with one or more UID bullets** (same parse rule `/implement` Step 1 uses). A requirement-scoped issue must go through `/implement` so the status transitions and traceability reconciliation run; `/quickfix` would intentionally skip them and ship the issue closed with the requirement lifecycle untouched. On a `## Requirements`-bearing issue, STOP and tell the user to re-invoke `/implement <issue-number>`.

An empty `## Requirements` section (heading present, zero UID bullets) is acceptable—it documents intent that the issue is requirement-free.

---

## Phase A: Resolve + Implement

### Step Q1: Resolve the Issue and Branch

**Reuses the issue-anchored mechanics of `skills/implement/SKILL.md` Step 1**, but **NOT** its UID classification / requirement-resolution path. Same `gc_get_repo_ground_control_context` call, same `gh issue develop --checkout --base {cfg.workflow.base_branch|default dev} --name <issue>-<short-slug>` invocation, same branch-shape post-check (≤ 50 chars, ASCII-only, `[a-z0-9-]`), same LinkedBranch repair when renaming, same in-progress label + pickup comment.

**Diverges from `/implement` Step 1 on input classification.** `/quickfix` accepts only issue references (plain integer, `#`-prefixed integer, or `issue:N`). If the user passed a requirement UID (anything matching the `<letters>-<letters/digits>` pattern), STOP and tell them to use `/implement <uid>`—the UID lane requires the requirement lifecycle (status transitions, traceability reconciliation) that `/quickfix` intentionally drops. Do NOT invoke the `/implement` UID-to-issue shim from this lane.

After the issue is fetched, run the **hard precondition** from the Invocation section above: parse the issue body's `## Requirements` section; reject if it has one or more UID bullets; an empty section is acceptable.

Do NOT duplicate the rest of the Step 1 prose here—read `skills/implement/SKILL.md` Step 1 for the branch/label/pickup mechanics in full; this skill defers to it verbatim for those.

### Step Q2.5: NO Codex Architecture Preflight

`/quickfix` skips preflight. The user invoked `/quickfix` because the design is settled. If during implementation the agent discovers the design is NOT settled (open architectural question, conflicting ADRs, ambiguous scope), STOP and either ask the user for direction or re-invoke `/implement <same-issue>` to upgrade the run.

### Step Q3: Light Codebase Coverage

Read the issue body + thread (`gh issue view <issue-number> --comments`). Glance at the cross-cutting concerns (`cfg.cross_cutting_concerns.description`) to use existing helpers rather than re-implementing them. Skip the full ADR / coding-standards / knowledge-base walk that `/implement` Step 3 does—for fix-shaped work it's overhead.

If the cross-cutting concerns inventory turns up something non-trivial (a canonical incumbent the fix should build on, an existing helper that does this exact thing already), use it. The "use existing helpers" rule is not optional just because the ceremony is lighter.

### Step Q4: NO Plan Post

No `gc_post_implementation_plan` call. The pickup comment (Step Q1) is the durable record of "this is being worked on"; the PR description (Step Q9) is the durable record of "this is what shipped." A separate plan-comment phase would be ceremony for ceremony's sake when the design is settled.

If during implementation the diff grows unexpectedly large (10+ files) or surfaces design decisions you can't resolve from context, STOP and either ask the user or re-invoke `/implement` to enter the planning lane.

### Step Q4.4: Implement

Apply the fix. TDD is **encouraged** but not policed for `/quickfix` runs—for a one-file parser bug the test that catches it usually drops in alongside the fix without a formal red-green-refactor cycle. The existing test suite + Step Q6 completion gate are the safety net.

The full TDD discipline from `skills/implement/SKILL.md` Step 4.4 (write failing test first, watch it fail for the right reason, make it pass with minimum code, refactor with green, repeat per clause) applies whenever the fix introduces new behavior. It's just not enforced as a per-clause invariant for fix-shaped work.

---

## Phase B: Quality Gate

### Step Q5: Pre-commit

**Identical to `skills/implement/SKILL.md` Step 5.** Run `pre-commit run --all-files` until clean (up to 5 retries; escalate to user on the sixth failure).

### Step Q6: Completion Gate

**Identical to `skills/implement/SKILL.md` Step 6.** All four checks apply, non-negotiable:

1. Completion gate command exits successfully (`cfg.workflow.completion_command` or `cfg.workflow.test_command` fallback).
2. Changelog fragment present for source-changing diffs (`changelog.d/<issue>.<type>.md`).
3. Clause/criterion mapping done. For `/quickfix` runs the issue title + body + any user comments are the acceptance contract (`/quickfix` runs are requirement-free by definition—if the issue has a `## Requirements` section, the user should be using `/implement` instead).
4. Documentation-only carve-out re-validation (path check + content check). Same rules as `/implement`.

Do NOT move to Phase C until all four pass.

### Step Q6.5 + Step Q6.6: AI-Assisted Reviews (OFF by default; `--review` to enable)

`/quickfix` skips both pre-push AI-assisted reviews by default. CI and SonarCloud (Steps Q10 / Q11) still run post-push and remain non-negotiable; the codex + test-quality reviewers are the optional add-ons.

When the user invokes `/quickfix --review <issue>`:

- **Step Q6.5 = codex pre-push review.** Run `gc_codex_review` with `uncommitted=true` against the staged + unstaged diff, exactly as `/implement` Step 6.5 describes. Default cap is **1 cycle** (per the same `.ground-control.yaml::workflow.codex_review.pre_push_cap` knob `/implement` uses; per issue #906). Apply the Review loop rules; post `gc_post_decision_record` per cycle; respect the cap; `override_cap=true` + `override_reason` works the same way.
- **Step Q6.6 = test-quality pre-push review.** Run `gc_test_quality_review` exactly as `/implement` Step 6.6 describes. Default cap 1 (`workflow.test_quality_review.pre_push_cap`). Same Review loop rules. Same decision-record contract.

When `--review` is absent, both steps skip. The skill still posts no decision records (the issue-thread durable record for a `/quickfix` run is the pickup comment + the open PR + the `gc_post_final_report` close comment in Step Q19; codex/test-quality records exist only when the reviewer actually ran).

---

## Phase C: Stage, Commit, Push

### Step Q7: Stage & Pre-commit Loop

**Identical to `skills/implement/SKILL.md` Step 7.**

### Step Q8: Commit & Push

**Identical to `skills/implement/SKILL.md` Step 8.** Imperative-mood commit message, no agent attribution, `git push -u origin <branch>`.

---

## Phase D: Ship

### Step Q9: Create PR

**Identical to `skills/implement/SKILL.md` Step 9**—same `gc_render_pr_body` call (with `requirement_uids: []` since `/quickfix` runs are requirement-free), same PR-title validation rules (single conventional-commit type + lowercase subject + per-repo override via `workflow.pr_title`), same `Closes #<issue-number>` wiring through the renderer.

The renderer's `change_class` is typically `source` for `/quickfix` runs; `doc-only` for pure documentation fixes; `source+migration` is unusual for `/quickfix` and is a signal that the run probably wanted `/implement` instead.

### Step Q10: CI Monitor

**Identical to `skills/implement/SKILL.md` Step 10.** Bounded poll (5-min queued-too-long guard, 45-min in-progress cap), diagnose-and-fix loop on failure.

### Step Q11: SonarCloud

**Identical to `skills/implement/SKILL.md` Step 11.** Quality gate + open-issues sweep + security hotspots. 5-iteration cap on fix → re-analyze cycles. Same `$SONAR_TOKEN`-direct REST fallback.

If `cfg.sonarcloud` is null, skip; proceed to Step Q18.

### Steps Q15–Q17: NO Requirement Transitions or Traceability Reconciliation

`/quickfix` runs are scoped to fix-shaped work, not requirement-shaped work—no `gc_transition_status` calls, no `gc_create_traceability_link` reconciliation against in-scope UIDs. The `in_scope_requirements[]` list is by definition empty for a `/quickfix` run.

**Exception: link maintenance on touched files.** If the diff modifies a file that has an existing IMPLEMENTS / TESTS link to some requirement and the behavior moved, update that link per `skills/implement/SKILL.md` Step 16's deletion-and-renaming rules (same `gc_get_traceability_by_artifact` + `gc_delete_traceability_link` + `gc_create_traceability_link` pattern). Default for a typical `/quickfix` run is no link changes; the fix preserves behavior, and the existing links remain valid.

If a `/quickfix` run touches files in a way that warrants requirement transitions, that's a signal the run should have been `/implement`. Surface to the user and re-invoke `/implement <same-issue>` rather than partial-completing the requirement work in the lighter lane.

### Step Q18: Clear In-Progress Label

**Identical to `skills/implement/SKILL.md` Step 18.** The GitHub issue closes via `Closes #<issue-number>` in the PR body (rendered by `gc_render_pr_body` in Step Q9) at PR merge. Step Q18 only removes the `in-progress` label: `gh issue edit <issue-number> --remove-label in-progress`. Do NOT run `gh issue close` from the agent: closing decouples the close event from the merge, and a rolled-back PR would leave a closed issue with no shipped code (GitHub does not re-open on revert).

### Step Q19: Lightweight Close Comment (via `gc_post_final_report`)

`/quickfix` calls `gc_post_final_report` with **`lane: "quickfix"`** (issue #906) and a slim payload: empty `requirements: []`, empty (or one-line-per-reviewer) `reviews: []`, no `traceability` block, a one-paragraph `summary`, the open `pr_number`, and `ci_status` / `sonar_status` reflecting the actual Step Q10 / Q11 results. The `lane: "quickfix"` flag relaxes the runner's "reviews must be non-empty AND contain a codex entry" gate (which exists for `/implement` Step 19's mandatory pre-push codex review record); `/quickfix` runs default with reviews off, so an empty `reviews[]` is the canonical shape. Every other gate stays in force: the tool runs `detectSensitiveBodyContent` and the canonical sensitive-content / no-defer / reserved-marker scrubs before any GitHub post, so the close comment can never carry an accidental token, secret, or raw command transcript onto the public issue thread. A direct `gh issue comment --body "..."` post would bypass those server-side filters, and the `.claude/hooks/*.py` PreToolUse hooks are Claude-Code-only and do not protect Codex or other drivers, so the MCP-tool boundary is the only driver-neutral enforcement layer.

The slim payload should populate:

- `lane`: `"quickfix"` (required to unlock the empty-reviews relaxation).
- `summary`: one-paragraph description of the fix (what broke, what now works). Update length follows the canonical succinctness rule in `skills/implement/steps/_review-loop-rules.md`.
- `reviews`: one entry per reviewer that ran (for example, `{reviewer: "codex", summary: "1 cycle, 0 findings"}`). Empty array when `--review` was not supplied.
- `requirements`: empty array (`/quickfix` is requirement-free by precondition; if link maintenance touched UIDs per the Q15–Q17 exception, mention them in `summary` rather than fabricating a requirement entry).
- `pr_number`: the **open** PR number from Step Q9—the comment is posted before the user-owned merge, so do NOT wait for the PR to be merged before calling this. The PR URL is rendered into the comment body by the tool.
- `ci_status` / `sonar_status`: `"green"` / `"passed"` for a successful run; `"skipped"` only when `cfg.sonarcloud` is null.

**You MUST NOT merge the PR.** Same rule as `/implement` Step 19. The user reviews and merges. Step Q19 runs **before** the merge; any prose suggesting a "merged PR link" is incorrect—at this point the PR is still open by contract.

---

## What `/quickfix` keeps (non-negotiable)

Every mechanical guardrail the repo enforces. Adding to this list is a `bin/policy` change, not a skill change.

- **Issue-as-entry-point** (ADR-029): every change anchored to a GitHub issue.
- **Branch-name shape** (issue #864 amendment to ADR-021): `<issue>-<slug>`, ≤ 50 chars, ASCII-only, `[a-z0-9-]`. Post-check enforced.
- **In-progress label + pickup comment** (issue #842).
- **No-defer language** in commit messages, PR body, issue comments (issue #830 PreToolUse hook + `bin/policy`).
- **Changelog fragment** for source-changing diffs (issue #848). Path-based, no "pure refactor" carve-out.
- **PR-title rules** (issue #901). Single conventional-commit type + lowercase subject; per-repo override via `workflow.pr_title`.
- **`gc_render_pr_body`** for the PR body (ADR-036) so `tools/policy/checks.py::check_pr_body` accepts it.
- **CI + SonarCloud green** before merge handoff.
- **`make check` + `make policy` clean** before commit (Step Q6 / pre-commit).
- **User merges, not the agent.**

## What `/quickfix` drops (compared to `/implement`)

Each drop is intentional and reversible mid-flight by re-invoking `/implement <same-issue>`.

- **Codex architecture preflight** (`gc_codex_architecture_preflight`)—the design is settled at intake.
- **Plan post + plan-phase marker** (`gc_post_implementation_plan`)—diff is the plan; PR is the durable record.
- **Pre-push codex review** + **pre-push test-quality review** by default—off unless `--review` is supplied. Both still respect the configured cap (default 1) when enabled.
- **Final-report tool full payload.** `gc_post_final_report` still runs (so its sensitive-content / no-defer / reserved-marker scrubs protect the public close comment on every driver), but with a slim payload: empty `requirements`, empty-or-one-line-per-reviewer `reviews`, no `traceability` block. The structured tool boundary is the only driver-neutral filter; a direct `gh issue comment` would bypass it.
- **Requirement status transitions** (`gc_transition_status`)—`/quickfix` runs are requirement-free by definition.
- **Traceability reconciliation** (`gc_create_traceability_link` / `gc_delete_traceability_link`)—only the touched-file link-maintenance path runs (and even that is rare for a typical `/quickfix` run).

## Upgrading mid-flight

If at any step the agent realizes the work warrants `/implement` discipline (the diff grew, design forks surfaced, requirement transitions are needed, codex/test-quality reviews would have caught something), STOP and ask the user. Two options:

1. Re-invoke `/implement <same-issue>` from where you are. The branch, in-progress signal, and any pushed commits carry over; `/implement` Step 1 will recognize the branch and resume.
2. Continue `/quickfix` with `--review` to add the pre-push AI-assisted reviewers without the full preflight + plan ceremony.

The user picks. Do not silently upgrade.

## References

- `skills/implement/SKILL.md`—canonical full workflow this skill mirrors.
- ADR-021 (Gated Agentic Development Loop)—the lane contract this builds on.
- ADR-029 (Issue-Thread Gate Model)—durable record + decision-record contract.
- ADR-031 (Codex Review Stopping Model)—the cap-1 default + override semantics this skill inherits.
- ADR-036 (Per-Step Routing / Tool Surfaces / Telemetry)—routing tier semantics + MCP-tool surfaces.
- `architecture/notes/quickfix-workflow-lane-preflight.md`—preflight design context for this skill.

## Amendments

**2026-05-19 (issue #931).** When the optional codex pre-push review is
invoked from /quickfix, it returns the same verdict envelope as the
/implement lane (`verdict` + `architectural_read` + `blocking` + capped
`notes`). The /quickfix slim close comment is unchanged;
`gc_post_final_report` with `lane="quickfix"` still drops empty reviews /
traceability sections. The /quickfix lane does NOT consume
`.ground-control.yaml::architecture.vocabulary` itself; that block is
consumed only by the pre-push reviewers and the preflight (when invoked).

**2026-05-21 (issue #937).** When `--review` is supplied, the optional codex
and test-quality pre-push reviews (Steps Q6.5 / Q6.6) run as async background
jobs: `gc_codex_review` / `gc_test_quality_review` are called with
`async: true` and the job is polled via the new `gc_codex_job` tool, exactly
as the /implement Step 6.5 / 6.6 prose this lane defers to now describes. The
cap-1 default, `override_cap` semantics, and the `gc_post_decision_record`
contract this lane inherits are unchanged—async only changes how the agent
waits for a review cycle's result, so the multi-minute child process is not
orphaned by the MCP client's tool-call timeout (issue #893). See ADR-036
(amendments) for the job model.

**2026-05-26 (issue #989).** A sibling lane, `/integrate`, now exists for preparing queues of approved PRs (GC-O011). The /quickfix and /integrate lanes are disjoint: /quickfix is issue-anchored and accepts the same `gc_post_decision_record` / `gc_post_final_report` contract as /implement; /integrate is repo-scoped and operational-only, with no issue-thread durable record. There is no overlap in trigger conditions or durable-record surfaces.
