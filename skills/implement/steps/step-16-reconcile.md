---
stage_id: transition_reconcile
step: "Step 16"
tier: medium
---

# Step 16: Reconcile Traceability Links Against the Diff

Now that CI and all reviews are green AND every materially-implemented in-scope requirement is ACTIVE, reconcile the Ground Control traceability graph against the actual diff. This MUST happen AFTER Step 15 and BEFORE Step 19.

**No deferral, here or anywhere downstream (ADR-029).** Reconciliation, the final report (Step 19), and any issue comment you post from here on must not contain deferral-disposition language — "out of scope for this PR", "follow-up issue to track X", "addressed in a subsequent PR", "deferred to a later iteration", "TBD later". A finding or a piece of work is either fixed in this PR, recorded `wontfix` with explicit user authorization, or recorded `not-applicable` with rationale. If reconciliation surfaces missing implementation for a requirement, that is a STOP-and-escalate, not a "noted as a follow-up". The `.claude/hooks/block-defer-language.py` PreToolUse hook will block a `gh issue comment` / `gh pr edit` carrying that language; do not work around it — re-route to fix-or-escalate.

**Reconciliation is not the same as "create links for the in-scope requirements"**. Even runs with zero in-scope requirements (pure bug fixes, refactors, maintenance) must reconcile, because the diff may have touched files that were already linked to OTHER requirements.

1. **Compute the touched file set.** Run `git diff --name-status <base-ref>...HEAD`. Cache the full list.

   Resolve `<base-ref>` using the configured base branch (`{cfg.workflow.base_branch|default dev}`) in this order:
   1. `origin/{cfg.workflow.base_branch|default dev}` (verify with `git rev-parse --verify`).
   2. `{cfg.workflow.base_branch|default dev}` (local, verify with `git rev-parse --verify`).
   3. `origin/main` (fallback for repos that don't use `dev`).
   4. `main` (fallback).
   5. If none resolve, run `git fetch origin {cfg.workflow.base_branch|default dev}` and retry. If the fetch fails (no network/no remote), STOP and surface a clear error.

2. **Process deleted and renamed files first.**
   For every deleted file `path`:
   - Call `gc_get_traceability_by_artifact` with `artifact_type: CODE_FILE` and `artifact_identifier: path`. Repeat with `artifact_type: TEST`.
   - For each link found: if behavior moved to a new file (rename or split), delete the stale link and create a replacement at the new path. If behavior was removed entirely and the linked requirement no longer has any implementation, STOP — ripping out the only implementation of a requirement is a user decision.
   For every renamed file `old_path → new_path`:
   - Delete the stale link and re-create it at `new_path`.

3. **Process modified files.**
   For every modified file `path`:
   - Call `gc_get_traceability_by_artifact` with `artifact_type: CODE_FILE` (or `TEST` for test files).
   - For each existing link, decide: still satisfies the linked requirement? → leave alone. Behavior moved? → delete stale link, create at new location. Behavior now spans more files? → add additional links.
   - Inspect for behaviors that satisfy under-linked requirements and create new links. Bound by plausible subject area; don't compare every requirement to every file.

4. **Process added files.**
   For every added file `path`:
   - Determine which requirement(s) it satisfies. Create IMPLEMENTS (production) or TESTS (test files) links via `gc_create_traceability_link`. Incidental files (helpers, fixtures, generated) may have no link — that's fine.

5. **Ensure every in-scope requirement has coverage appropriate to its nature.**

   **Mode A — the diff ships the work.** For each UID in `in_scope_requirements[]`:
   - **IMPLEMENTS coverage is required against the artifact(s) of record.** Every materially-implemented in-scope requirement must have at least one IMPLEMENTS link pointing at the file(s) that actually satisfy its clauses. Those files may be in the diff (Step 15 case in-diff), pre-date the diff (Step 15 case pre-existing — finalization/documentation runs whose structural implementation lives in a sibling requirement's earlier code), or both. The shape of "implementation" depends on the requirement: code requirements → production file; documentation requirements → ADR/SCHEMA/docs file; configuration requirements → config file; workflow requirements → workflow file / hook script. When the diff itself adds documentation that defines the requirement's contract (e.g., an ADR section), link that ADR with IMPLEMENTS too; pure-housekeeping diff entries (e.g., changelog fragments under `changelog.d/`) need no link.
   - **Backfill onto pre-existing artifacts when the diff finalizes the requirement.** If Step 15 classified the requirement as case pre-existing, the *backfill targets* are already cached from Step 15's discovery procedure, partitioned by intended link type. Create IMPLEMENTS links pointing at each candidate in the IMPLEMENTS partition; create TESTS links pointing at each candidate in the TESTS partition. Use `gc_create_traceability_link` with the partition's link type. Never create an IMPLEMENTS link onto a candidate classified as a TEST. Apply the *Backfill rules* below. Do not invent a "diff-only" IMPLEMENTS link onto an ADR or changelog fragment as a substitute for linking the actual implementing code.
   - **TESTS coverage is conditional.** Add a TESTS link when the diff introduces or touches an automated test that verifies the requirement, OR when discovery's TESTS partition (Step 15) contains a pre-existing automated test that verifies it. TESTS is NOT required for documentation / configuration / structural-invariant requirements with no executable behavior. IMPLEMENTS alone is the complete coverage record in those cases.
   - **Do not fabricate test links.** If a requirement has testable behavior and no test was added or discovered, go back to Step 4.4.
   - **Never link the diff to a requirement it does not satisfy** just to satisfy this step. Surface the mismatch to the user instead.
   - **Forward-looking requirements** (DRAFT requirements this PR does not materially implement) get DOCUMENTS links, not IMPLEMENTS, and stay DRAFT.

   **Backfill rules (apply to Mode A case pre-existing and to Mode B below).**
   - Reuse the discovery procedure documented in Step 15 (subject-area-bounded `git ls-files` / `grep` against named subsystems/file roots, then `gc_get_traceability_by_artifact` per candidate). Do NOT compare every requirement to every file in the repo.
   - Existing links to files that still satisfy the requirement remain valid. Do not churn links merely because the current PR touched a nearby document.
   - If backfill discovers no implementing file anywhere in the repo, STOP — either the requirement should be demoted (DEPRECATED) or implementation is missing. Surface to the user. (For Mode A case pre-existing this should be impossible — Step 15 would have refused the transition first.)

   **Mode B — Step 4 concluded the work is already complete.** The diff is empty (Step 4 step 5 path); forcing an IMPLEMENTS link onto a non-existent diff file would be wrong. Instead:
   - **Accept existing IMPLEMENTS coverage.** If the requirement already has IMPLEMENTS links pointing at files that exist and still satisfy it, that coverage is complete. Do NOT fabricate new links.
   - **Backfill only when nothing is linked.** If the requirement has zero existing IMPLEMENTS links, locate the implementing file(s) per the *Backfill rules* above and create the link(s).
   - **TESTS rules from Mode A still apply.**

6. **Reconcile the issue → requirement links (both directions).**
   - **Add missing links.** For each UID in `in_scope_requirements[]`, ensure there is a `GITHUB_ISSUE` link with `artifact_identifier: <issue-number>` on the requirement. Use `IMPLEMENTS` for materially-implemented requirements (matches `gc_create_github_issue`'s auto-link convention), `DOCUMENTS` for forward-looking ones. **Never** use `TESTS` — an issue is not an executable test. Material implementation status of the *code* is captured by separate `IMPLEMENTS` links from the requirement to actual code/ADR/config files (Step 5 above).
   - **Delete stale links.** Call `gc_get_traceability_by_artifact` with `artifact_type: GITHUB_ISSUE` and `artifact_identifier: <issue-number>`. For each returned link, if the requirement UID is NOT in `in_scope_requirements[]`, delete it.
   - Duplicate-create is intentionally rejected by `gc_create_traceability_link`, so re-adding is safe.
   - Note: `gc_create_github_issue` auto-creates `IMPLEMENTS` issue→requirement links during UID-first runs (Step 6 of Step 1). That is correct for materially-implemented requirements. For a forward-looking in-scope requirement that this PR does not deliver, manually delete the auto-`IMPLEMENTS` link and replace with a `DOCUMENTS` link before this reconciliation step exits.

Reconciliation is idempotent: running it on an already-correct branch is a no-op.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "links_added": [ "<UID> ← <path> (<link_type>)" ],
    "links_updated": [ "<UID> ← <old_path> → <new_path>" ],
    "links_deleted": [ "<UID> ← <path>" ],
    "notes": "<optional one-line note>"
  }
}
```
