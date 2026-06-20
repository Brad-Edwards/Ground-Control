---
stage_id: grc_screening
step: "Step 3.5"
tier: medium
---

# Step 3.5: GRC Screening Gate

Before planning, classify the planned change surface against the project's existing threat-model and risk-scenario workspaces. Record one of three verdicts as a durable screening record on the issue thread.

## Verdicts

- **`security_relevant`**: The planned change touches a security-relevant surface. Threat-model entries, risk scenarios, controls, and CODE links must be created, updated, or confirmed before the completion gate. Record the UIDs and code paths.
- **`not_security_relevant`**: The planned change does not touch a security-relevant surface. Provide a one-line rationale. No silent skip.
- **`no_baseline`**: The project has no threat-model baseline. Record this as an explicit declination, not as a clean verdict.

## Steps

1. Call `gc_threat_model_workspace` and `gc_risk_scenario_workspace` to read the project's existing baseline. Pass the project identifier from `cfg.project`.
2. Classify the planned change surface by reviewing the in-scope requirements (`in_scope_requirements[]`) and the issue description. Consider which code paths, data flows, trust boundaries, and authentication/authorization surfaces will be touched.
3. Select the appropriate verdict:
   - **Empty baseline** → verdict `no_baseline`. Skip to step 5.
   - **Change does not touch a security-relevant surface** → verdict `not_security_relevant`. Write a concise rationale (one to three sentences). Skip to step 5.
   - **Change touches a security-relevant surface** → verdict `security_relevant`. Proceed to step 4.
4. For `security_relevant` runs only - before posting the record:
   - Use `gc_threat_model`, `gc_risk_scenario`, and `gc_control` to create, update, or confirm the threat-model entries and risk scenarios that the planned change affects.
   - Use the existing link tools to attach `targetType=CODE` links from the affected threat-model or risk-scenario entities to the repo-relative paths that will change.
   - Collect the UIDs of every entity created, updated, or confirmed, and every CODE link's `target_identifier`.
5. Post the screening record by calling `gc_post_grc_screening`:
   - `repo_path`: the absolute repository root from Step 1.
   - `issue_number`: the issue number from Step 1.
   - `verdict`: one of `security_relevant`, `not_security_relevant`, `no_baseline`.
   - `rationale`: a concise explanation (required for all three verdicts).
   - `entities_created`, `entities_updated`, `entities_confirmed`: typed ref arrays (`{type, uid}`) populated for `security_relevant`; empty arrays for the other verdicts.
   - `code_links`: typed ref array (`{owner_type, owner_uid, target_identifier}`) with at least one entry for `security_relevant`; empty for the others.

## Constraints

- Only `gc_post_grc_screening` may post the durable screening record. Do not use `gh issue comment`, `curl`, or raw GitHub API calls for this record.
- Do not infer `security_relevant` from filenames alone. File paths are an input to classification, not proof that the threat/risk graph is reconciled.
- Do not conflate `no_baseline` with `not_security_relevant`. Missing baseline is an explicit declination, not a clean verdict.
- Do not create a `security_relevant` record unless concrete UIDs and CODE links were created, updated, or confirmed during this step.

## Handoff to next step

The screening record is durable workflow state. The `grc_screening` phase marker written by the tool signals the gate completed. The envelope returned by `gc_post_grc_screening` carries the screening verdict and comment URL for caching.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "grc_screening_verdict": "<security_relevant|not_security_relevant|no_baseline>",
    "grc_screening_comment_url": "<url>",
    "entities_created": [],
    "entities_updated": [],
    "entities_confirmed": [],
    "code_links": []
  }
}
```
