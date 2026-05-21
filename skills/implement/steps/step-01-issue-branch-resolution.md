---
stage_id: issue_branch_resolution
step: "Step 1"
tier: low
---

# Step 1: Resolve the Issue and Branch

1. Run `pwd` to capture the absolute repository root.

2. Call the `gc_get_repo_ground_control_context` MCP tool with:
   - `repo_path`: absolute path from `pwd`

3. If the tool does NOT return `status: "ok"`:
   - Stop immediately.
   - Tell the user the repository is missing a valid `.ground-control.yaml` at its root.
   - Include the tool's `suggested_ground_control_yaml` in your response and ask the user to create the file before using `/implement`.
   - Do NOT guess the Ground Control project.

4. If the tool returns `status: "ok"`, cache the following fields (referred to as `cfg.X` throughout the rest of this skill):
   - `cfg.project` — used in all Ground Control MCP calls
   - `cfg.workflow.test_command` — the test suite command
   - `cfg.workflow.completion_command` — the full completion gate (falls back to test_command if null)
   - `cfg.workflow.lint_command` — the linter
   - `cfg.workflow.format_command` — the formatter
   - `cfg.sonarcloud` — if set, used by Step 11; if null, SonarCloud is skipped
   - `cfg.rules.plan_rules_content` — if non-null, mandatory plan constraints (Step 4)
   - `cfg.docs.*` — repo documentation paths (Step 3)
   - `cfg.example_paths.*` — repo source/test path examples (Step 4.5)
   - `cfg.requirements.uid_examples` — repo's requirement UID style examples
   - `cfg.cross_cutting_concerns.description` — repo's cross-cutting concern hint (Step 3)

5. **Classify `$ARGUMENTS` as either an issue reference or a requirement UID**:
   - **Issue reference**: a plain integer (`123`), a `#`-prefixed integer (`#123`), or a form like `issue:123`. In all cases, strip to the integer and treat it as the GitHub issue number.
   - **Requirement UID**: anything matching the pattern `<letters>-<letters/digits>` (examples in this repo: `{cfg.requirements.uid_examples|default ["GC-X001", "OBS-042"]}`). Treat the entire string as the UID exactly as provided. Do NOT synthesize or rewrite a project prefix.
   - If the input fits neither pattern, stop and ask the user for disambiguation.

6. **If the input was a requirement UID**, resolve it to a GitHub issue:
   1. Use `gc_get_requirement` with the UID and `cfg.project`. If the requirement does not exist, stop and report it.
   2. Use `gc_get_traceability` with the requirement's UUID. Look for a link with `artifact_type: GITHUB_ISSUE`.
   3. If such a link exists, note the issue number from its `artifact_identifier`.
   4. If no link exists, use `gc_create_github_issue` with the UID and `cfg.project` to create an issue and auto-link it. Note the new issue number.
   - From this point forward, treat the resolved issue number as the authoritative input. The requirement UID becomes a single entry in the `in_scope_requirements[]` list computed below.

7. **Fetch the issue** via `gc_get_issue_thread` (issue #934) with `repo_path` and `issue_number`. The first call returns the full body + comments + a content hash. Cache the hash; the orchestrator will pass it forward so downstream steps short-circuit when the thread hasn't changed.

8. **Parse the `in_scope_requirements[]` list from the issue body.** The convention is a Markdown section whose heading is exactly `## Requirements` (case-insensitive match on the word `Requirements`, any heading level from `##` to `####`). The section contains a bulleted list where each bullet is a Ground Control requirement UID, optionally followed by prose explanation.
   - If the section is present and non-empty, every valid UID bullet becomes an entry in `in_scope_requirements[]`.
   - If the section is present and empty, `in_scope_requirements[]` is empty — this is a bug/refactor/maintenance run with no formal requirements.
   - If the section is absent entirely, `in_scope_requirements[]` is empty.
   - If a UID in the section fails to resolve via `gc_get_requirement`, stop and report the broken reference.
   - If the input was a requirement UID (Step 6), ensure that UID is in `in_scope_requirements[]` — add it if missing, which also means updating the issue body to include a Requirements section.

9. **For each UID in `in_scope_requirements[]`**, fetch the full requirement via `gc_get_requirement` and cache its UUID, title, statement, status, and wave. You will use these for clause verification (Step 4.5), status transitions (Step 15), and traceability reconciliation (Step 16).

10. **Fetch the existing traceability links for the issue** via `gc_get_traceability_by_artifact` with `artifact_type: GITHUB_ISSUE` and `artifact_identifier: <issue-number>`. Cache the result — you will need it to reconcile the issue's relationship to requirements in Step 16.

11. **Switch to the issue's feature branch**: run `gh issue develop <issue-number> --checkout --base {cfg.workflow.base_branch|default dev} --name <issue-number>-<short-slug>`.

    **Always pass `--name` with a short slug.** Without it, `gh` derives the branch name from the full issue title and you get monstrosities like `55-the-ec2-instance-management-permissions-are-overly-broad-with-resource-=-this-allows-terraform-to-manage-any-ec2-instance-in-the-account-not-just-the-ones-it-creates-consider-scoping-these-permissions-using-tags-or-name-patterns-for-example`. These break terminal display, copy-paste, CI logs, GitHub UI breadcrumbs, and any downstream tool that touches the branch name. The total branch name (`<issue-number>-<short-slug>`) MUST be ≤ 50 characters; aim for 30–40.

    To derive `<short-slug>`: read the issue title, pick the 2–4 words that name the *thing being changed* (the noun and the verb on it), drop filler words (`the`, `a`, `with`, `for`, `consider`, etc.), kebab-case them, and ASCII-only (no `→`, no Unicode arrows, no slashes, no equals signs — `gh` and most CI tools tolerate them but they regularly break shell quoting downstream). Examples:

    - Title: "Verify GC-T004 (Risk Treatment Plans): clause-by-clause audit, transition DRAFT→ACTIVE, reconcile traceability" → `--name 825-verify-gc-t004`.
    - Title: "The ec2 instance management permissions are overly broad with resource = *. This allows terraform to manage any EC2 instance in the account, not just the ones it creates. Consider scoping these permissions using tags or name patterns, for example..." → `--name 55-scope-ec2-iam`.
    - Title: "GC-T004 / C8: categorised reassessment triggers + event publisher / listener wiring" → `--name 863-gc-t004-c8-triggers`.

    If the branch already exists (issue was previously picked up), `gh` reuses it and your `--name` is ignored — you stay on whatever branch the previous pickup created.

    Then run `git branch --show-current` and cache that exact string as `<branch>` — sub-step 12 uses it verbatim; do not reconstruct it from the issue number.

    **Validate the actual checked-out branch against the same rule** — if the previous pickup ran before this rule existed (or didn't follow it), the cached `<branch>` will violate the invariant and would otherwise flow through the pickup comment, push, CI breadcrumbs, and the PR head ref. Check `<branch>`:

    - **Compliant** when it starts with `<issue-number>-`, is ≤ 50 characters, contains only `[a-z0-9-]` (no Unicode arrows, no slashes, no `=`), and has at least one `-` after the number prefix. Proceed to sub-step 12.
    - **Non-compliant AND safe to rename.** "Safe to rename" requires BOTH (i) zero commits relative to the configured base branch on the *remote* (the local base may be stale; fetch first), and (ii) no PR exists for this branch. Concretely:
      ```
      git fetch origin {cfg.workflow.base_branch|default dev}
      git rev-list --count origin/{cfg.workflow.base_branch|default dev}..HEAD   # must equal 0
      gh pr list --head <branch> --json number                                    # must equal []
      ```
      When both predicates hold: rename in place AND repair the issue's `LinkedBranch` so the GitHub Development sidebar matches the new branch. The full sequence is mandatory — skipping the LinkedBranch repair leaves the issue pointing at the deleted branch (silently) AND can hand a subsequent `gh issue develop` re-pickup the dangling metadata, which is the original failure mode this post-check exists to prevent:
      ```
      # 1. Local + remote rename.
      git branch -m <new-compliant-name>
      git push origin :<old-branch> <new-compliant-name>

      # 2. Find the existing LinkedBranch id (may be empty if the branch was created manually).
      OLD_LINK_ID=$(gh api graphql -f query='
        query($owner:String!, $repo:String!, $number:Int!) {
          repository(owner:$owner, name:$repo) {
            issue(number:$number) {
              linkedBranches(first: 50) { nodes { id ref { name } } }
            }
          }
        }
      ' -F owner=<owner> -F repo=<repo> -F number=<issue-number> \
        --jq '.data.repository.issue.linkedBranches.nodes[] | select(.ref.name == "<old-branch>") | .id')

      # 3. If a stale LinkedBranch exists, delete it.
      if [ -n "$OLD_LINK_ID" ]; then
        gh api graphql -f query='
          mutation($id:ID!) { deleteLinkedBranch(input:{linkedBranchId:$id}) { issue { id } } }
        ' -F id="$OLD_LINK_ID"
      fi

      # 4. Resolve the issue node id and the new branch HEAD oid (which equals base SHA, since no commits exist).
      ISSUE_NODE_ID=$(gh api graphql -f query='
        query($owner:String!, $repo:String!, $number:Int!) {
          repository(owner:$owner, name:$repo) { issue(number:$number) { id } }
        }
      ' -F owner=<owner> -F repo=<repo> -F number=<issue-number> --jq '.data.repository.issue.id')
      HEAD_OID=$(git rev-parse HEAD)

      # 5. Create the new LinkedBranch.
      gh api graphql -f query='
        mutation($issueId:ID!, $oid:GitObjectID!) {
          createLinkedBranch(input:{issueId:$issueId, oid:$oid}) {
            linkedBranch { id ref { name } }
          }
        }
      ' -F issueId="$ISSUE_NODE_ID" -F oid="$HEAD_OID"
      ```
      Re-run `git branch --show-current` and re-cache `<branch>`. Continue to sub-step 12.

      `<owner>` and `<repo>` are the GitHub owner and repo for the current run (resolvable via `gh repo view --json owner,name`). The mutations are idempotent enough for the post-check's purpose: `deleteLinkedBranch` skipped when no stale link exists; `createLinkedBranch` errors with a recognizable conflict if a link with the same ref already exists (treat as success — the sidebar already points at the right branch).

    - **Non-compliant AND not safe to rename** (commits exist OR a PR exists): STOP. Renaming a published branch with an open PR is a force-push that breaks the PR head ref and any reviews already posted to inline comments. **Apply the in-progress signal first** — execute sub-step 12 in full (label + pickup comment) so the issue is visibly flagged as picked-up-but-paused, then post a SECOND issue comment summarizing the situation (current branch, commit count, PR number if any) and ask the user whether to (a) proceed with the long branch name as a one-shot exception (the rule is not yet repo-policy-enforced; the user owns the call), or (b) close the existing PR, delete the branch, and retry `/implement` from scratch on a fresh compliant branch. Wait for the user's answer; do not push commits while the question is open. The label stays set until Step 18 closes the issue OR the user resolves the situation by retrying on a fresh branch (in which case the new run's Step 1 sub-step 12 re-flags).

    The post-check is the dispositive enforcement of the invariant — `--name` only governs first-time pickups; this check governs every actual run.

12. **Flag the issue as in-progress** so a human scanning the issue list — or another agent — can see at a glance that work is underway. The values below are stated together so a repo that prefers a different visible signal changes one place; pass everything to `gh` as argv, never as a shell-interpolated string:
    - **label** `in-progress` — color `FBCA04`, description `An agent is actively working this issue via /implement`.
    - **pickup comment** — `🛠️ Picked up by /implement — driver <Claude Code | Codex>, branch \`<branch>\`, <ISO-8601 UTC timestamp>.` where `<branch>` is the value cached in sub-step 11 from `git branch --show-current`.

    Run, in order:
    1. `gh label create in-progress --color FBCA04 --description "An agent is actively working this issue via /implement" 2>/dev/null || true` — creates the label if the repo lacks it, and is a harmless no-op otherwise. Do NOT pass `--force`: `gh` treats `--force` as "overwrite", so it would rewrite a repo's existing `in-progress` label's color/description on every `/implement` run — broader than this issue-lifecycle signal. (If creation fails for a real reason — e.g., the token can't manage labels — the next sub-step's `gh issue edit --add-label` surfaces it.)
    2. `gh issue edit <issue-number> --add-label in-progress`.
    3. `gh issue comment <issue-number> --body "<pickup comment>"`.

    This is operational visibility only: the pickup comment is **not** a phase marker, the plan comment, a review-findings record, or the final report, and it gates nothing. If the label create/apply or the comment post fails, surface the failure before continuing to Step 2 — a silent failure defeats the duplicate-work signal. The label is **not** removed on an error path or a partial pause: a run that escalates to the user before Step 18 leaves the issue flagged, because it *was* picked up and the work is paused, not finished. Only Step 18 (issue closure) clears it.

## Return contract

On success return a compact envelope; do not echo verbose `gh` / `git` output to the parent:

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "repo_path": "<absolute path>",
    "issue_number": <int>,
    "branch": "<branch name>",
    "cfg": { "...full ground control context..." },
    "in_scope_requirements": [
      { "uid": "<UID>", "uuid": "<UUID>", "title": "<...>", "status": "<...>", "wave": <int> }
    ],
    "issue_thread_hash": "<sha256 hash from gc_get_issue_thread>",
    "issue_traceability_links": [ ... ]
  }
}
```

On any non-recoverable error: `{ "status": "error", "error": "<short error key>", "message": "<one-line>" }`.
