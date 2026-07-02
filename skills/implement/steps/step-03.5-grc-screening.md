---
stage_id: grc_screening
step: "Step 3.5"
tier: medium
---

# Step 3.5: GRC Screening Gate (derivation-backed)

Before planning, screen the change against the project's security model. Per **GC-GRC-009 / ADR-058 §5**, the verdict is **computed by the tool from the change itself** — the agent no longer asserts security relevance, and an empty baseline no longer short-circuits to a free pass. The tool derives three sets and records them as a durable, schema-versioned (`gc.implement.grc-screening/v2`) record on the issue thread.

## What the tool computes

`gc_post_grc_screening` reads the diff (base→head), the existing GRC CODE-link graph, and the latest/pinned derivation run + architecture-model snapshot, then derives:

- **`impact_set`** — existing GRC entities (threat models, risk scenarios, controls) whose `targetType=CODE` links overlap the touched paths. This is the existing coverage the change touches.
- **`gap_set`** — touched security-relevant surfaces with no coverage. Reasons: `no_derivation_coverage` (no derived facts model the surface — including the empty/absent-baseline case), `no_model_coverage`, `no_threat_coverage`, `no_control_coverage`. **There is no passing `no_baseline` verdict**: a missing baseline yields a `gap_set` over the touched surface (recorded with a capture limit), scoped to the changed boundaries — work this run must model, control, or disposition, not a recorded pass.
- **`stale_set`** — ACTIVE linked entities whose underlying code changed and may now be out of date.

When an architecture-model snapshot exists, the tool also attaches deterministic **GC-GRC-007 candidate threats** and **GC-GRC-008 candidate controls**. The record is reproducible from its recorded provenance (base/head SHAs, derivation run + snapshot ids, pack ids/versions/checksums, capture limits).

The `derived_verdict` is computed, not chosen: `not_security_relevant` when both `impact_set` and `gap_set` are empty; otherwise `security_relevant`.

## Steps

1. Determine the diff scope. The tool always derives the touched surface from the git diff (base = merge-base with the repo's base branch, head = HEAD); you may pin verifiable `base_commit_sha` / `commit_sha` refs or a `derivation_run_id`. There is no caller path override: the touched surface is never caller-supplied, so it cannot be narrowed or forged.
2. Optionally pass `threat_pack_id` (and `threat_pack_version`) to enumerate candidate threats/controls against the current snapshot.
3. Call `gc_post_grc_screening` with:
   - `repo_path`: the absolute repository root from Step 1.
   - `issue_number`: the issue number from Step 1.
   - `project` (optional; resolved from `.ground-control.yaml` when omitted).
   - optional scope inputs above, and an optional `rationale` (≤ 800 bytes) describing the change in your own words.
4. Read the returned `{derived_verdict, impact_count, gap_count, stale_count}`. A non-zero `gap_count` is unreconciled security work: it must be modeled, controlled, or dispositioned before the completion gate (the reconciliation gate blocks on a non-empty `gap_set`).

## Constraints

- **The agent does not assert the verdict, entities, or code links.** They are computed. Do not attempt to hand-pick a verdict; the tool no longer accepts one.
- Only `gc_post_grc_screening` may post the durable screening record. Do not use `gh issue comment`, `curl`, or raw GitHub API calls for this record.
- The tool does not infer security relevance from filenames. Non-source paths (docs, ADRs/notes, skills/workflow prose, changelog fragments, repo metadata, tests) are excluded from the touched surface; every remaining application-source path is classified by coverage, and absence of derivation coverage is a gap, never a pass.
- A missing baseline is scoped work for this run, never a `no_baseline` pass.

## Handoff to next step

The screening record is durable workflow state. The `grc_screening` phase marker written by the tool signals the gate completed. `gc_assert_grc_reconciled` (post-merge, Phase E) does not trust the stored record: it **recomputes** the classification from the final diff (the record's recorded base commit to the current HEAD) against the live GRC graph and **blocks on the freshly-computed `gap_set`**, so source added after screening ran cannot bypass the gate. The only authorized bypass is `gc_post_final_report`'s `phaseOverride` (an audited disposition). Historical/in-flight v1 records continue to reconcile via the v1 verdict path.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "grc_screening_schema": "gc.implement.grc-screening/v2",
    "grc_screening_derived_verdict": "<security_relevant|not_security_relevant>",
    "grc_screening_comment_url": "<url>",
    "impact_count": 0,
    "gap_count": 0,
    "stale_count": 0
  }
}
```
