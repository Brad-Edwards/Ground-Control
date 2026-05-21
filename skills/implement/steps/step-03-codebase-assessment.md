---
stage_id: codebase_assessment
step: "Step 3"
tier: medium
---

# Step 3: Assess Codebase Coverage

Explore the codebase to determine whether the work described in the issue is already covered by existing code:
- Search for relevant classes, methods, tests, and configurations touching the subsystems the issue describes.
- Consult the repository's existing documentation during exploration. Each repo declares its design-context locations in `.ground-control.yaml`. Read:
  - ADRs at `{cfg.docs.adr_dir|default architecture/adrs/}`
  - Architecture overview at `{cfg.docs.architecture_overview|default docs/architecture/ARCHITECTURE.md}` (if present)
  - Coding standards at `{cfg.docs.coding_standards|default docs/CODING_STANDARDS.md}` (if present)
  - Workflow reference at `{cfg.docs.workflow_reference|default docs/DEVELOPMENT_WORKFLOW.md}` (if present)
  - Knowledge base at `{cfg.docs.knowledge_base|default cfg.knowledge.dir|default docs/knowledge/}` (if present). The `knowledge.dir` block (the structured knowledge MCP path) is the source of truth; `docs.knowledge_base` is an override only when documentation prose needs to point somewhere different than the structured knowledge directory. Keeping them aligned is the default.

  Grep these for keywords matching the issue's subject area and read any pages that match before designing changes.
- Check if the described behavior already exists.
- **Before designing new code, inventory the existing cross-cutting concerns the change will need.** Do not skip this — it is the single biggest defense against re-implementing helpers that already exist. Where the repo has supplied a hint:

  ```
  {cfg.cross_cutting_concerns.description|default
    Logger: project's chosen logging library; structured-logging conventions
    Validation / schemas: framework-native validation; schema definitions; runtime validators
    Error types and error-response builders: project's exception/error envelope
    Authentication / authorization helpers
    Configuration loaders and environment-variable access
    HTTP client / DB session wrappers, retry / backoff utilities
    Test fixtures, factory helpers, and mock builders
  }
  ```

  For each concern the new code will touch, find and read the project's existing implementation. Use the existing helper. If you genuinely need a new one, justify the new helper in the plan (Step 4) and note why the existing one didn't fit. Re-implementing what's already there is the failure mode the Step 6.5 codex review is designed to catch — catch it here first so you don't spend a review cycle on it.
- For each requirement in `in_scope_requirements[]`, review its existing traceability links (IMPLEMENTS, TESTS) via `gc_get_traceability` on the requirement UUID. Some or all clauses may already be satisfied.
- Reuse the architecture-preflight guidance from Step 2.5 while assessing existing coverage and planning changes.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "existing_coverage_summary": "<one paragraph: what already exists, what gaps remain>",
    "reusable_helpers": [ "<list of canonical helpers the change will build on, with file paths>" ],
    "files_to_modify": [ "<short list of file paths that will likely change>" ],
    "files_to_add": [ "<short list of file paths that will likely be added>" ]
  }
}
```

Do NOT return full file contents; only `file:line` references and short summaries.
