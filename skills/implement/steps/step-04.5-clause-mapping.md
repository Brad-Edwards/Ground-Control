---
stage_id: clause_mapping
step: "Step 4.5"
tier: medium
---

# Step 4.5: Clause-by-Clause Verification

Before declaring implementation complete, build a mapping from every clause of every in-scope requirement AND every acceptance criterion in the issue body to the specific code (`file:line`) that satisfies it.

1. For each requirement in `in_scope_requirements[]`:
   - Re-read the requirement statement cached in Step 1.
   - Break it into individual clauses.
   - For EACH clause, identify the specific code (`file:line`) that satisfies it.
2. For each acceptance criterion stated in the issue body (or in the issue comments by the user):
   - Identify the specific code (`file:line`) that satisfies it.
3. If `in_scope_requirements[]` is empty AND the issue body has no explicit acceptance criteria, treat the issue title and description as the acceptance contract and verify the change matches.
4. If any clause or criterion is not satisfied, go back and implement it before proceeding.

Present the mapping as a checklist with the requirement UID (or `issue`) as the source label. Use the repo's source/test path conventions:

```
- [ ] GC-X004 clause: "..." → Satisfied by: {cfg.example_paths.source|default <repo source path>}/.../File.java:line
- [ ] GC-X004 clause: "..." → Satisfied by: {cfg.example_paths.test|default <repo test path>}/.../FileTest.java:line
- [ ] GC-X005 clause: "..." → Satisfied by: <other-relevant-path>:line
- [ ] issue acceptance: "..." → Satisfied by: {cfg.docs.adr_dir|default architecture/adrs/}0XX-name.md:line
```

Do not proceed until every clause and criterion is checked off.

## Re-screen against the real diff (GC-GRC-009 / GC-GRC-010)

Step 3.5 ran **before** any code existed, so on a fresh run its diff was empty and the screening almost always computed `not_security_relevant` — which means GC-GRC-010's design-time deliverables gate could not fire and any real security relevance stays hidden until the post-merge reconciliation blocks on it (`grc_not_reconciled`). Close that gap here, now that the diff is real:

1. Re-run `gc_post_grc_screening` (Step 3.5) against the implemented diff. The tool always recomputes the touched surface from git, so this is a fresh, trustworthy classification — not a caller-asserted one.
2. If the `derived_verdict` is now `security_relevant` with a non-empty `gap_set` or `stale_set`, **model the GRC deliverables here** — the same secure-by-design work GC-GRC-010 would have required at plan time: model/confirm the threats, select and link the controls (with CODE + efficacy-test links via `gc_control`), and refresh the stale entities. Do it now rather than discovering it at the completion gate. A control that cannot be implemented in the change routes to a GC-GRC-015 disposition, never a silent pass.
3. The post-merge reconciliation (Step 17) recomputes the same sets and blocks on any residual `gap_set`, so this step is where you *avoid* that block, not a substitute for it.

Traceability reconciliation (IMPLEMENTS / TESTS links) and the `DRAFT → ACTIVE` status transitions are intentionally NOT done here. They land in **Phase E (Steps 15–16), after the PR merges** (issue #963), so Ground Control state never runs ahead of the actual code that ships - a reviewed-but-abandoned PR leaves the requirement DRAFT and unlinked.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "clause_mapping": [
      { "uid": "<UID or 'issue'>", "clause": "<short string>", "satisfied_by": "<file:line>" }
    ],
    "unmapped_clauses": []
  }
}
```

If `unmapped_clauses` is non-empty, return `status: "error"` so the orchestrator loops back to Step 4.4.
