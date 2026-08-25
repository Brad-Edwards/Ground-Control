---
stage_id: review_post_merge
step: "Step 03"
tier: medium
---

# Step 03: Post-merge Issue Closure

## Precondition — the PR is merged

This phase runs **only after merge is confirmed**. The user owns the merge; the lane never performs it. Enter this phase when the user asks to close the delivered issues, or immediately after they merge, and only once the PR shows `MERGED` with a non-null `merged_at`.

## Select

Rebuild the candidate set from `gc_get_pr_review_context`'s `linked_issues[]` (or a fresh call): `closing_reference` entries are authoritative delivery candidates; `cross_reference` entries are mentions, not delivery. For each candidate, inspect its **actual contract** against the merged diff and classify it:

- **directly delivered** — the PR's merged change satisfies the issue's acceptance criteria in full;
- **partially delivered** — the PR advances but does not complete the issue;
- **parent / tracking (epic)** — a broader tracker the PR contributes to.

A cross-reference, shared label, milestone, parent link, or mention in prose is **not** delivery evidence.

## Close

Close **only open, directly-delivered issues**, one at a time, through **`gc_close_issue_after_merge`** with `repo_path`, `issue_number`, and `pr_number`. That tool independently verifies the linked PR is merged, verifies the issue↔PR linkage, and is idempotent (an already-closed issue is a no-op) — those checks stay authoritative; do not close issues with `gh` directly.

Leave partially-delivered issues and parent/tracking epics **open**. GitHub's own `Closes #n` keyword may already have auto-closed a directly-delivered issue at merge; `gc_close_issue_after_merge` no-ops in that case, which is expected.

Post nothing else. This phase closes issues; it does not comment, relabel, or reopen.
