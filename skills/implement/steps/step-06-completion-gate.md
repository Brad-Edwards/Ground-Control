---
stage_id: completion_gate
step: "Step 6"
tier: low
---

# Step 6: Completion Gate

On the normal path call `gc_implement_mechanical` with `action="verify"`,
`requirements` populated from Step 1, the issue/repository identifiers,
`async=true`, and one bounded `idempotency_key` for this verification attempt.
Poll the returned `job_id` through `gc_codex_job` until `status="done"`, then
dispatch on `result`. Reuse the same key only if the start response was lost;
after repairing a failed gate, create a new key for the new attempt. The action
runs the configured completion command, the configured policy command, and
verifies that those gates did not mutate the checkout. Continue only on
`ok: true`; a failed envelope names the exact gate an agent must repair before
retrying the same action.

Implementation is NOT ready for commit until ALL of the following are verified:

1. **Completion gate passes once on the final Phase-B implementation tree** -
   the `verify` action runs `cfg.workflow.completion_command`, falling back to
   `cfg.workflow.test_command` when necessary. If neither is configured, its
   result names that configuration defect. Repair the configuration or ask the
   user for the repository's intended command; do not guess or run a substitute
   gate. Do not retry when the relevant tree state is unchanged.
2. **Step 4.5 clause mapping was completed** - if you skipped it, go back and do it now.
3. **If the documentation-only carve-out from Step 4.4 was declared**, re-validate it against the *actual* diff right now. The check must cover both committed AND uncommitted/untracked changes, since Step 6 runs *before* the Step 7/8 stage-and-commit step:
   1. **Compute the full path set.** Take the union of `git diff --name-only <base-ref>...HEAD` (committed work), `git diff --name-only HEAD` (unstaged), `git diff --cached --name-only` (staged), and `git ls-files --others --exclude-standard` (untracked-but-not-ignored). Working-tree state is part of the diff at this point in the workflow.
   2. **Path check (necessary, not sufficient).** Confirm every path in the union is documentation. The documentation set is intentionally narrow: `*.md`, `architecture/adrs/**`, `architecture/notes/**`, `docs/**`, `README.md`, `skills/**/*.md`, and equivalent doc-only locations declared by the repo (`CHANGELOG.md` is Release Please's, not a feature-PR doc path - see `docs/DEVELOPMENT_WORKFLOW.md § Release model`). Any path outside that set - `*.java`, `*.py`, `*.ts`, `*.tsx`, `*.js`, `*.kts`, `*.gradle`, `*.yaml`/`*.yml` (workflows, configs), `*.sh`, `*.sql`, `Dockerfile`, `Makefile`, `*.json` (policies, package manifests, lockfiles), etc. - invalidates the carve-out outright.
   3. **Content check (the path check is not enough - a doc file can carry executable behavior).** For each path that survived check (2), inspect the actual diff content with `git diff <base-ref> -- <path>` (which against the working tree covers both committed and uncommitted changes). If any hunk introduces executable behavior - code fences whose contents are intended to be executed by tooling, embedded YAML that a code path parses and acts on, schema/grammar/policy data consumed by a runtime parser, runnable test fixtures, or any other line of static text whose meaning is "what the program should do at runtime" - the carve-out is invalidated for those clauses, and the mandatory red-green loop applies (write the failing test against the parser/consumer, then make the doc edit pass it).
   4. The carve-out passes Step 6 only when BOTH checks pass: every path is in the documentation set AND no diff hunk introduces executable behavior. If either check fails, revert to the mandatory red-green loop for the failing portion before declaring the gate passed.

4. **Repository policy passes once on the same tree.** Run
   `cfg.workflow.policy_command` only through the `verify` action (default
   `make policy`). A repo whose gate is named differently sets that field; the
   gate is never skipped because a target is absent. In this repository it also
   runs Vale on docs touched in the diff. If
   `.tools/vale/current/vale` is missing, run `bash tools/install-vale.sh` and
   retry - do not skip. Vale enforces the Google Developer Documentation Style
   Guide and Diátaxis structure (see ADR-054 and `docs/DOC_STYLE.md`); fix every
   reported error before proceeding. Whole-file scope on touch: pre-existing
   violations in any diff-touched `.md` are part of the diff's required
   cleanup. Cache this successful tree state so review steps can avoid
   redundant broad reruns; if review fixes later change the tree, the review
   band reruns completion and policy once on its final post-fix state.

5. **The real quality signals are CI and Sonar, checked in the monitor band.**
   There is no backend quality-gate evaluation (issue #1500): CI (GitHub) and
   Sonar (direct-to-Sonar) are the authoritative quality signals and are checked
   at Steps 10/11 after publish. The `verify` action's job here is the local
   gates above — the completion command (item 1), the policy gate (item 4), and
   the no-tree-change guard. Do NOT proceed to Phase C while any of those fails.

If any check fails, fix it before proceeding. Do NOT move to Phase C until every check passes.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "completion_gate_passed": true,
    "policy_gate_passed": true,
    "verified_tree_state": "<git tree/diff identity>",
    "carveout_revalidated": false,
    "quality_gates_passed": true
  }
}
```
