---
stage_id: pr_body
step: "Step 9"
tier: low
---

# Step 9: Create PR

1. Check if a PR already exists for this branch: `gh pr list --head <branch> --json number,url`
2. If no PR exists, **render the PR body via `gc_render_pr_body`** (per ADR-036). Pass:
   - `repo_path`, `issue_number`
   - `change_class`: `doc-only` if the diff is entirely documentation per Step 6's two-check sweep; `source+migration` if the diff includes a database migration; otherwise `source`.
   - `requirement_uids`: the in-scope UIDs from Step 1.
   - `adr_refs`: the ADR identifiers this PR touches (e.g. `ADR-036`, `ADR-021 (amended)`); pass an empty array to render "No ADR required".
   - `summary`: one paragraph.
   - `changes`: array of bullet strings describing each change.
   - `traceability`: `{ implements: [...], tests: [...] }` strings — typically `<UID> ← <file path>` shape; the tool emits the IMPLEMENTS / TESTS markers `check_pr_body` requires.
   - `changelog_fragment`: path under `changelog.d/` (required for `source` / `source+migration`; omit for `doc-only`).
   - `test_notes` (optional): extra prose under the Test Plan section.

   The tool returns `{ ok, body, byte_length }`. Use `body` as the PR description.

   **No-requirements runs** (`in_scope_requirements[]` is empty — typical for bug fixes, refactors, dependency bumps): the renderer emits `- (none — bug/refactor/maintenance run; see Traceability section below)` in the Requirement UIDs section. The whole-body UID check (`PR_REQUIREMENT_RE`) still needs SOME UID-shaped token in the body — for these runs, satisfy it by passing at least one ADR reference in `adr_refs`. ADR identifiers like `ADR-021` / `ADR-029` / `ADR-036` match the UID regex. Use `ADR-021` ("Gated Agentic Development Loop") as the foundational default for any `/implement`-driven PR, since every `/implement` run is gated by ADR-021 — that's not fabricated traceability, it's an honest acknowledgment of the workflow contract that produced the PR.

3. **Shape the PR title locally before calling `gh pr create`.** Downstream Ground-Control-aware repos run `amannn/action-semantic-pull-request` (or an equivalent conventional-commit title linter) and reject titles that fail either of two rules. Catching them here removes the edit-cycle-per-failure cost the agent otherwise pays after every `gh pr create`.

   - **Rule 1 — single conventional-commit type with optional scope.** Title must match `<type>(<optional-scope>): <subject>`. Compound type prefixes like `security/docs:` or `fix/refactor:` are rejected outright. For bundled PRs that ship multiple change classes, pick the dominant type (typically `security:` for security/initial-release bundles, even when docs/refactor changes ride along) and describe the rest in the subject. The canonical allow-list, when the repo does not declare its own (see configuration knob below), is: `security`, `added`, `changed`, `deprecated`, `removed`, `fixed`, `feat`, `fix`, `chore`, `docs`, `refactor`, `test`, `ci`, `build`, `perf`, `revert`.
   - **Rule 2 — subject starts lowercase** (matches `^[a-z].*$`). Project or acronym names that conventionally appear uppercase (`NGFW`, `GCP`, `AWS`, `MCP`) must be reshaped so the **first character of the subject is lowercase**. Either lowercase the leading word (`ngfw doc cleanup`), relocate it inside a path prefix (`mcp/ngfw doc cleanup` — slash-prefixed paths read as lowercase), or front the sentence with a verb (`harden mcp/ngfw secret handling`).
   - **Configuration knob (per repo).** When `.ground-control.yaml` declares a `workflow.pr_title` block, consume it; otherwise fall back to the canonical defaults above.
     ```yaml
     workflow:
       pr_title:
         types: [security, added, changed, deprecated, removed, fixed,
                 feat, fix, chore, docs, refactor, test, ci, build, perf, revert]
         subject_pattern: "^[a-z].*$"
         require_scope: false
     ```
   - **Failure handling.** If the title fails either rule, reshape it and re-validate locally. Do NOT push to GitHub and let the lint workflow report it — that costs a full CI cycle for a one-line edit.

4. Create the PR with the rendered body:
   ```
   gh pr create --base {cfg.workflow.base_branch|default dev} --title "<validated title>" --body "<rendered body from gc_render_pr_body>"
   ```
   The renderer emits `Closes #<issue-number>` under Related Issues, so GitHub's UI cross-link is wired automatically.

5. Note the PR number and URL. Tier for this step: `low` — the tool does the rendering; the agent just collects structured input.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "pr_number": <int>,
    "pr_url": "<URL>",
    "change_class": "doc-only" | "source" | "source+migration"
  }
}
```
