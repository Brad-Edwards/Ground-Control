---
stage_id: completion_gate
step: "Step 6"
tier: low
---

# Step 6: Completion Gate

Implementation is NOT ready for commit until ALL of the following are verified:

1. **Completion gate passes**—run `cfg.workflow.completion_command`. If that field is null, fall back to `cfg.workflow.test_command`. If both are null, ask the user what the completion gate command should be for this repo (do not guess). Confirm the command exits successfully.
2. **Changelog fragment present (or legitimately absent)**—if the diff touches application source (backend/frontend/MCP source, or `tools/` outside `tools/policy/` and `tools/tests/`), `git diff --name-only` MUST contain a valid fragment under `changelog.d/<issue>.<type>.md` (or `changelog.d/+<slug>.<type>.md`), type ∈ `security`/`added`/`changed`/`deprecated`/`removed`/`fixed`. A direct `CHANGELOG.md` edit does NOT satisfy a source-changing diff—that branch would re-open the rebase-storm pathology the convention exists to prevent. Direct `CHANGELOG.md` edits are reserved for release-collation commits, which by definition touch only `CHANGELOG.md` and the fragments they consumed (no source). CI-only diffs (only `.github/workflows/`) and docs-only diffs (`docs/**`, `architecture/**`, `README.md`, `CONTRIBUTING.md`, `.gc/**`, `skills/**`, etc.) carry no source paths and require no signal. `make policy` and `.claude/hooks/verify-implementation.sh` both encode the same predicate; if either flags `changelog-signal-missing` / `changelog-fragment-invalid-name`, add or rename the fragment.
3. **Step 4.5 clause mapping was completed**—if you skipped it, go back and do it now.
4. **If the documentation-only carve-out from Step 4.4 was declared**, re-validate it against the *actual* diff right now. The check must cover both committed AND uncommitted/untracked changes, since Step 6 runs *before* the Step 7/8 stage-and-commit step:
   1. **Compute the full path set.** Take the union of `git diff --name-only <base-ref>...HEAD` (committed work), `git diff --name-only HEAD` (unstaged), `git diff --cached --name-only` (staged), and `git ls-files --others --exclude-standard` (untracked-but-not-ignored). Working-tree state is part of the diff at this point in the workflow.
   2. **Path check (necessary, not sufficient).** Confirm every path in the union is documentation. The documentation set is intentionally narrow: `*.md`, `architecture/adrs/**`, `architecture/notes/**`, `docs/**`, `CHANGELOG.md`, `changelog.d/**`, `README.md`, `skills/**/*.md`, and equivalent doc-only locations declared by the repo. Any path outside that set—`*.java`, `*.py`, `*.ts`, `*.tsx`, `*.js`, `*.kts`, `*.gradle`, `*.yaml`/`*.yml` (workflows, configs), `*.sh`, `*.sql`, `Dockerfile`, `Makefile`, `*.json` (policies, package manifests, lockfiles), etc.—invalidates the carve-out outright.
   3. **Content check (the path check is not enough—a doc file can carry executable behavior).** For each path that survived check (2), inspect the actual diff content with `git diff <base-ref> -- <path>` (which against the working tree covers both committed and uncommitted changes). If any hunk introduces executable behavior—code fences whose contents are intended to be executed by tooling, embedded YAML that a code path parses and acts on, schema/grammar/policy data consumed by a runtime parser, runnable test fixtures, or any other line of static text whose meaning is "what the program should do at runtime"—the carve-out is invalidated for those clauses, and the mandatory red-green loop applies (write the failing test against the parser/consumer, then make the doc edit pass it).
   4. The carve-out passes Step 6 only when BOTH checks pass: every path is in the documentation set AND no diff hunk introduces executable behavior. If either check fails, revert to the mandatory red-green loop for the failing portion before declaring the gate passed.

5. **Vale prose linting (ADR-054).** `make policy` runs Vale on docs touched in the diff. If `.tools/vale/current/vale` is missing, run `bash tools/install-vale.sh` and retry—do not skip. Vale enforces the Google Developer Documentation Style Guide and Diátaxis structure (see ADR-054 and `docs/DOC_STYLE.md`); fix every reported error before proceeding. Whole-file scope on touch: pre-existing violations in any diff-touched `.md` are part of the diff's required cleanup.

6. **Quality gates pass.** Call `gc_assert_quality_gates` with `project: cfg.project` and `requirements` populated from `in_scope_requirements[]` as `[{uid, status_intent}]`. The tool evaluates the project's enabled quality gates server-side (`QualityGateService.evaluate`) and returns a mechanical pass/fail envelope—this is not an agent judgment call. If it returns `ok: false`, the run is **blocked**. Project-level failures return `failing_gates[]` with each failing gate as `{name, metric_type, threshold, actual}` (only the gates that failed), so the metric to fix is obvious from the error alone. When the active `DOCUMENTS` coverage gate exists, the same call also checks every in-scope requirement for a `DOCUMENTS` traceability link regardless of status; missing links return `error: "in_scope_documentation_coverage_failed"` plus `missing_documents[]`. Fix the underlying metric—add the missing IMPLEMENTS / TESTS / DOCUMENTS traceability links, resolve orphaned or incomplete requirements—then re-run. The enforced metric types are COVERAGE (over IMPLEMENTS / TESTS / DOCUMENTS link coverage), ORPHAN_COUNT, and COMPLETENESS. Do NOT proceed to Phase C while any enabled gate fails. (If `gc_assert_quality_gates` is not yet exposed by the running MCP server—it shipped in issue #1101—fall back to `gc_quality_gate action=evaluate` with the same project and block on `passed: false`; the dedicated tool applies once the server restarts. The fallback cannot perform the in-scope requirement check, so prefer the dedicated tool.)

If any check fails, fix it before proceeding. Do NOT move to Phase C until every check passes.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "completion_gate_passed": true,
    "changelog_fragment_path": "<path or null when carve-out applies>",
    "carveout_revalidated": false,
    "quality_gates_passed": true
  }
}
```
