---
stage_id: transition_reconcile
step: "Step 15"
tier: medium
---

# Step 15: Transition In-Scope Requirements to ACTIVE

The status transition MUST happen BEFORE traceability reconciliation (Step 16). The Ground Control API enforces `IMPLEMENTS → ACTIVE`: any `gc_create_traceability_link` call with `link_type: IMPLEMENTS` against a `DRAFT` requirement returns `422 requirement_not_active`. Reconciling first therefore produces silent failures.

Semantically, moving a requirement from DRAFT to ACTIVE is the point at which the team commits to its statement. Once real code exists pointing at it, the requirement is no longer a proposal — it's a contract.

For each UID in `in_scope_requirements[]`:
- **First, classify the requirement against the actual diff:**
  - **Materially implemented (case in-diff)** — the diff itself contains the artifacts-of-record that satisfy the requirement's clauses (production code, tests, schema/migration files, configuration files, ADRs, workflow definitions, skill prose, or any other deliverable the requirement statement specifies).
  - **Materially implemented (case pre-existing)** — the diff finalizes/documents the requirement (e.g., an ADR clarification, changelog fragment, or workflow note marking the requirement complete) while the structural implementation already exists in pre-existing files shipped under a sibling requirement. The test is "does this PR ship the requirement," not "is the implementing code in this diff." For this case, **before transitioning the requirement**, use the discovery procedure below to identify the pre-existing artifact(s) of record. If discovery finds zero implementing files, the case-pre-existing classification is wrong: STOP, surface to the user (the requirement is either forward-looking, has missing implementation, or was misidentified), and do NOT transition.
  - **Forward-looking** — the diff documents or references the requirement but does not ship it (e.g., a schema field that an unimplemented future feature will consume, or a design note that anticipates work in a later wave). Use a `DOCUMENTS` link in Step 16 instead of `IMPLEMENTS`, and leave the status DRAFT. Surface this decision as a comment on the issue. Skip the rest of this loop for that UID.

- **Pre-existing artifact discovery procedure (case pre-existing only).** This MUST run before the ACTIVE transition for the case-pre-existing path, so Ground Control never gets promoted-without-coverage:
  1. Read the requirement statement and identify the named subsystems, file roots, modules, or component identifiers it references (e.g., "the Identity Center bootstrap module," "the state-boundary verifier," "the ingest pipeline's retry helper").
  2. Run `git ls-files` filtered to those roots, plus `git grep -l` (NOT `grep -r`) against the requirement's distinctive identifiers (UID, named module, distinctive function names) bounded to the subject-area paths. Use `git`-aware tools so the candidate set only contains tracked files — `grep -r` would also walk untracked / generated / `.gitignore`'d / build / `node_modules` paths and produce candidates for files that were never shipped, which would create traceability links to non-shipped artifacts. Do NOT scan the whole repo.
  3. **Validate each candidate file against the requirement statement** by reading it and confirming the file actually satisfies the clause(s) you mapped it to. The candidate list from grep/ls-files is a superset; the agent's read of file content against the requirement is what proves satisfaction. Discard candidates that do not actually satisfy the requirement.
  4. **For each surviving candidate**, classify it by intended link type and call `gc_get_traceability_by_artifact` to learn what it is already linked to (dedupe / preservation, NOT validation):
     - Production code, configuration files, ADR/design docs, workflow files → IMPLEMENTS link, with `artifact_type: CODE_FILE` / `CONFIG` / `ADR` / `DOCUMENTATION` as appropriate.
     - Automated tests that verify the requirement → TESTS link, with `artifact_type: TEST`.
     - Existing links to the same requirement remain valid; do not churn them. Existing links to a different requirement that still satisfy that requirement also remain valid; do not delete them.
  5. Cache the resulting candidate set as the *backfill targets*, partitioned by intended link type (IMPLEMENTS targets vs TESTS targets) — Step 16 Mode A reuses this partitioned set rather than re-discovering, and never creates an IMPLEMENTS link onto a candidate classified as a TEST.
  6. If the IMPLEMENTS partition is empty after a bounded, validated search, the case-pre-existing classification fails (see above).

- **Only after classification (and, for case pre-existing, after discovery succeeded)**, transition the materially-implemented requirements:
  - Use `gc_transition_status` to transition the requirement from `DRAFT` to `ACTIVE`.
  - If the requirement was already `ACTIVE`, skip it.
  - If the requirement was in any other state (`DEPRECATED`, `ARCHIVED`), STOP and surface the anomaly to the user — transitioning out of those states is a user decision.

If `in_scope_requirements[]` is empty, this step is a no-op. Proceed to Step 16 anyway — reconciliation still needs to run to catch drift on other requirements whose files this diff touched.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "transitions": [
      { "uid": "<UID>", "from": "DRAFT", "to": "ACTIVE" }
    ],
    "forward_looking": [ "<UID>" ],
    "backfill_targets": {
      "<UID>": { "implements": [ "<path>" ], "tests": [ "<path>" ] }
    }
  }
}
```
