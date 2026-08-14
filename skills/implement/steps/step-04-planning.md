---
stage_id: planning
step: "Step 4"
tier: high
---

# Step 4: Plan and Post to Issue

Per ADR-029, the plan is **published to the GitHub issue as a comment** and the workflow proceeds directly to TDD. There is no synchronous user-approval gate.

The primary invocation session performs this step. Routing metadata may suggest
a capability tier/model, but Ground Control does not select another executor or
force delegation.

1. **If the work is NOT yet complete**: produce a written plan and post it as an issue comment. Identify which files need to be created or modified, what tests to write, and what approach to take. Update length follows the canonical succinctness rule in `skills/implement/steps/_review-loop-rules.md`.
   - When `in_scope_requirements[]` is non-empty, the plan must cover every clause of every in-scope requirement. When it is empty, the plan must fully address every acceptance criterion in the issue body and any user clarifications in comments.
   - Assign a `tdd_path` to each clause and acceptance criterion: Path A (new
     requirement or feature), Path B (bug fix on shipped code), Path C
     (reviewer-finding fix), or Path D (prose-only or static contract
     narrowing). The issue-level `implementation_intent` from Step 1 is only a
     hint; the plan's per-clause classification is authoritative, and mixed
     work applies the paths independently.
   - **Structural-gate runs need a GC requirement, even when `in_scope_requirements[]` is empty.** If the plan introduces a new ADR-backed structural gate (a new MCP tool, a new policy check in `tools/policy/checks.py`, a new `/implement` step, or any other executable enforcement layer), the plan must anchor that gate on a Ground Control requirement so traceability links exist. Two valid paths: (a) link the new artifacts to an existing umbrella requirement when the work clearly fits one - for example, additions to the `/implement` gated workflow fit `GC-O007` "Gated Agentic Development Loop"; (b) create a new requirement file `docs/requirements/<UID>/requirement.md` (status `DRAFT`) when the gate has its own distinct contract that no existing requirement covers, then transition it to `ACTIVE` at Step 15. The architecture preflight's "no requirement introduced" non-goal is a starting recommendation, not a binding directive - the planner overrides when the diff ships a new gate. Shipping a structural gate without traceability is the failure mode this rule exists to prevent.
   - Plans must respect the coding standards and formal methods classification levels.
   - Add or update ADRs as appropriate.
   - Do NOT edit `CHANGELOG.md` and do NOT file a `changelog.d/` fragment - that convention is retired (GC-P027, issue #1399). Release Please owns `CHANGELOG.md` and the product version, deriving both mechanically from Conventional Commit history on `main` via its release PR. The plan's only obligation here is that the eventual PR title (Step 9) will be a valid Conventional Commit type/subject; see `docs/DEVELOPMENT_WORKFLOW.md § Release model`. Plans must still update the readme and docs as appropriate.
   - Build off existing cross-cutting concerns, code, and patterns (Step 3).
   - Code should be readable, maintainable, and follow the coding standards.
   - Address the concerns a FAANG L6+ engineer would have around security, performance, reliability, and scalability.
   - Avoid reinventing the wheel - use existing libraries and frameworks where appropriate.
   - Simple is better than complex.
   - If `cfg.rules.plan_rules_content` is non-null, treat every bullet in that content as a mandatory plan constraint (repo-specific "plans MUST..." rules).
   - If `cfg.workflow.dev_start_gate.enabled` is `true`, the plan must include a `## Dev-Start Gate` section before calling `gc_post_implementation_plan`. For docs/design/config-only work, include `Source-bearing: no` and a concrete `Non-source rationale:` explaining why the change does not start application-source implementation. For source-bearing work, include `Source-bearing: yes`, every field in `cfg.workflow.dev_start_gate.required_fields`, and one `<UID> applicability:` line for every UID in `cfg.workflow.dev_start_gate.blocker_uids`; each applicability value must start with `applies`, `not-applicable`, or `not applicable`. High-risk plans (`Verification risk score` with `total>=4`) must also include concrete `High-risk verification evidence:`.
   - **Design with the repository in view, not just the file you're editing.** The plan must demonstrate the design was considered against all four of: **security** (every cross-cutting layer the change passes through that has a `validate()` / shape-check / parser / policy gate - auth surface, secret-handling, env/config binding shapes, OS-level exposure like a token in process argv, error-envelope leakage - name each layer and how the design satisfies it); **maintainability** (the canonical incumbents - config, script, helper - the change must build on, reuse over new abstraction); **extensibility** (the next obvious change in the same direction; whether the design forecloses it; the seam/parameter that keeps one future variation from re-editing the canonical artifact); **whole-repo view** (the canonical configs, canonical scripts, cross-cutting rules, and host/OS/runtime layers that will see the artifact - enumerate the ones in scope). A design that "sits correctly within the edited file's existing style" but fails a validator outside that file, or that re-implements a canonical incumbent, is the failure this requirement exists to catch *at plan time* rather than at codex-review time. The codex architecture preflight (Step 2.5) is asked the same four questions; reconcile its answers into the plan.

2. **Post the plan to the issue thread** via the `gc_post_implementation_plan` MCP tool with:
   - `repo_path`: absolute path from Step 1
   - `issue_number`: the issue number from Step 1
   - `plan_body`: the full plan as a Markdown string

   The tool refuses unless a `preflight` phase marker exists for this issue. If you skipped Step 2.5, the gate refuses and instructs you to run the missing step first; do not work around the refusal by `gh issue comment` directly. If `cfg.workflow.dev_start_gate.enabled` is `true`, the tool also refuses a missing or invalid `## Dev-Start Gate` section and returns `next_action: add_valid_dev_start_gate_to_plan_and_retry`. The tool also writes a `plan` phase marker so downstream tools can confirm planning happened.

   Cache the returned comment URL for the final report (Step 19).

3. **Do not wait for user approval.** Proceed directly to Step 4.4 (TDD). The issue thread is the durable record of the plan; if the user has feedback they can comment on the issue and the agent can revise mid-flight.

4. **Pause for genuinely subtle questions only.** If preflight, codebase coverage, or planning surfaced a design decision you cannot resolve from context (architectural fork, conflicting ADRs, ambiguous requirement scope), use a clarification mechanism appropriate to the driver (`AskUserQuestion` in Claude Code; equivalent prompt in Codex) BEFORE posting the plan, and finalize the plan with the user's answer. The default is to proceed without asking.

5. **If the work is ALREADY complete** (existing code already satisfies every clause of every in-scope requirement): post a *completion report* on the issue using the same `gh issue comment` mechanism. The report identifies which code satisfies the requirements (with `file:line` references). If `in_scope_requirements[]` is non-empty, verify each requirement is already linked and ACTIVE; if not, continue to Steps 15–16 (transition then reconciliation) to fix the Ground Control state without re-implementing the code.

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
