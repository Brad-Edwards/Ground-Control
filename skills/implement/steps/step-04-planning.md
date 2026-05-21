---
stage_id: planning
step: "Step 4"
tier: high
---

# Step 4: Plan and Post to Issue

Per ADR-029, the plan is **published to the GitHub issue as a comment** and the workflow proceeds directly to TDD. There is no synchronous user-approval gate.

This step runs in the **parent** agent (`agent: parent` in the routing config). Architectural reasoning lives at the parent level; subagents implement.

1. **If the work is NOT yet complete**: produce a written plan and post it as an issue comment. Identify which files need to be created or modified, what tests to write, and what approach to take.
   - When `in_scope_requirements[]` is non-empty, the plan must cover every clause of every in-scope requirement. When it is empty, the plan must fully address every acceptance criterion in the issue body and any user clarifications in comments.
   - Plans must respect the coding standards and formal methods classification levels.
   - Add or update ADRs as appropriate.
   - Plans whose diff touches application source MUST drop a changelog fragment under `changelog.d/<issue>.<type>.md` (or `changelog.d/+<slug>.<type>.md` for issue-free entries), where `<type>` is one of `security`, `added`, `changed`, `deprecated`, `removed`, `fixed`. CI-only diffs (only `.github/workflows/`) and docs-only diffs (only `docs/**`, `architecture/**`, `README.md`, `CONTRIBUTING.md`, `.gc/**`, `skills/**`, etc.) may ship without a fragment. There is no "pure refactor" carve-out — the policy is path-based, so a behavior-preserving refactor under an application-source path still files a fragment (one-line `### Changed - Internal refactor of X` is fine). Do NOT edit `CHANGELOG.md` directly — release-time `towncrier build` collates fragments. See the repo's `changelog.d/README.md` for the naming convention. Plans must still update the readme and docs as appropriate.
   - Build off existing cross-cutting concerns, code, and patterns (Step 3).
   - Code should be readable, maintainable, and follow the coding standards.
   - Address the concerns a FAANG L6+ engineer would have around security, performance, reliability, and scalability.
   - Avoid reinventing the wheel — use existing libraries and frameworks where appropriate.
   - Simple is better than complex.
   - If `cfg.rules.plan_rules_content` is non-null, treat every bullet in that content as a mandatory plan constraint (repo-specific "plans MUST..." rules).
   - **Design with the repository in view, not just the file you're editing.** The plan must demonstrate the design was considered against all four of: **security** (every cross-cutting layer the change passes through that has a `validate()` / shape-check / parser / policy gate — auth surface, secret-handling, env/config binding shapes, OS-level exposure like a token in process argv, error-envelope leakage — name each layer and how the design satisfies it); **maintainability** (the canonical incumbents — config, script, helper — the change must build on, reuse over new abstraction); **extensibility** (the next obvious change in the same direction; whether the design forecloses it; the seam/parameter that keeps one future variation from re-editing the canonical artifact); **whole-repo view** (the canonical configs, canonical scripts, cross-cutting rules, and host/OS/runtime layers that will see the artifact — enumerate the ones in scope). A design that "sits correctly within the edited file's existing style" but fails a validator outside that file, or that re-implements a canonical incumbent, is the failure this requirement exists to catch *at plan time* rather than at codex-review time. The codex architecture preflight (Step 2.5) is asked the same four questions; reconcile its answers into the plan.

2. **Post the plan to the issue thread** via the `gc_post_implementation_plan` MCP tool with:
   - `repo_path`: absolute path from Step 1
   - `issue_number`: the issue number from Step 1
   - `plan_body`: the full plan as a Markdown string

   The tool refuses unless a `preflight` phase marker exists for this issue (per #794 MVP-2 — `gc_codex_architecture_preflight` writes that marker on success). If you skipped Step 2.5, this gate will refuse the plan post and instruct you to run preflight first; do not work around the refusal by `gh issue comment` directly. The tool also writes a `plan` phase marker so downstream tools can confirm planning happened.

   Cache the returned comment URL for the final report (Step 19).

3. **Do not wait for user approval.** Proceed directly to Step 4.4 (TDD). The issue thread is the durable record of the plan; if the user has feedback they can comment on the issue and the agent can revise mid-flight.

4. **Pause for genuinely subtle questions only.** If preflight, codebase coverage, or planning surfaced a design decision you cannot resolve from context (architectural fork, conflicting ADRs, ambiguous requirement scope), use a clarification mechanism appropriate to the driver (`AskUserQuestion` in Claude Code; equivalent prompt in Codex) BEFORE posting the plan, and finalize the plan with the user's answer. The default is to proceed without asking.

5. **If the work is ALREADY complete** (existing code already satisfies every clause of every in-scope requirement): post a *completion report* on the issue using the same `gh issue comment` mechanism. The report identifies which code satisfies the requirement(s) (with `file:line` references). If `in_scope_requirements[]` is non-empty, verify each requirement is already linked and ACTIVE; if not, continue to Steps 15–16 (transition then reconciliation) to fix the Ground Control state without re-implementing the code.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "plan_comment_url": "<URL from gc_post_implementation_plan>",
    "plan_comment_id": <int>,
    "plan_phase_marker_written": true,
    "work_already_complete": false,
    "doc_only_carveout_declared": false,
    "carveout_structural_gates": [ "<gate name per clause>" ]
  }
}
```

When the work is already complete (sub-step 5 path), return `work_already_complete: true` and omit `plan_comment_url`. The orchestrator will skip Steps 4.4 / 4.5 / 5 / 6 and jump to Steps 15+ (transition + reconcile).
