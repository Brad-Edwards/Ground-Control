# Documentation Coverage Workflow Preflight

Issue #896 makes documentation coverage an explicit `/implement` workflow gate
for changes that touch workflow behavior, MCP tool surfaces, config parsing,
policy, ADRs, public APIs, or user-visible behavior. This note is architecture
preflight guidance only. It does not implement the workflow step, MCP tooling,
policy checks, report rendering, or documentation updates.

## Architecture Boundaries

- Treat documentation coverage as a workflow gate, not as reviewer memory. The
  durable evidence belongs in the GitHub issue thread and PR/final-report
  records per ADR-029.
- Keep the canonical workflow source in `skills/implement/` and the executable
  enforcement in MCP/policy surfaces. Do not add prompt-only instructions that
  no tool or repo-native gate can verify.
- Keep `.ground-control.yaml` under the single
  `gc_get_repo_ground_control_context` parser contract. Documentation of the
  config shape must be reconciled against `parseGroundControlYaml`,
  `getRepoGroundControlContext`, and their tests; do not create a second
  accepted-shape schema in prose.
- Keep final evidence structured. A documentation outcome such as "updated,"
  "verified unchanged," or "not updated with explicit authorization" should be
  represented in deterministic PR/final-report inputs or rendered sections, not
  hidden in optional free-form summaries.
- Preserve ADR-029's zero-deferral model. If policy ever permits an
  intentionally postponed documentation update, it must require explicit user
  authorization and a durable rationale; it is not a silent escape hatch.

## Cross-Cutting Concerns to Reuse

- **Workflow source:** reuse the per-step orchestrator pattern in
  `skills/implement/SKILL.md` and `skills/implement/steps/`. A documentation
  coverage step should have a stable stage id if it participates in routing or
  telemetry, and it must stay in the existing phase model.
- **Config parsing:** reuse `parseGroundControlYaml`,
  `getRepoGroundControlContext`, strict unknown-key rejection, defaults, and
  path containment checks in `mcp/ground-control/lib.js`. Config docs must cover
  every accepted key, default, enum, path rule, and validation constraint.
- **Report rendering:** reuse `gc_render_pr_body` and `gc_post_final_report`
  renderer boundaries rather than adding free-form `gh issue comment` or PR
  body prose. Renderer validation should reject incomplete documentation
  outcomes before anything is posted.
- **Policy gates:** reuse `bin/policy`, `tools/policy/checks.py`,
  `architecture/policies/adr-policy.json`, and `tools/tests/test_policy.py` for
  repo-native drift checks. Existing examples include workflow guardrail sync,
  controller/API/MCP parity, changelog fragments, and PR-body shape checks.
- **GitHub side effects:** privileged writes stay in the MCP server through the
  existing `gh api` argv-based posting path, with sensitive-content and
  reserved-marker filtering.
- **Tests:** pure classifiers, renderers, and config-shape checks belong in
  `mcp/ground-control/lib.test.js`; policy behavior belongs in
  `tools/tests/test_policy.py`. Do not add substring tests that only assert
  documentation text exists.

## Security and Validation Layers

- **Changed-surface classifier:** any tool or policy check that compares changed
  executable/config surfaces to documentation must classify paths with a closed
  vocabulary and bounded repo-relative paths. Do not traverse arbitrary paths
  from config or issue text.
- **Config-path containment:** docs paths, architecture vocabulary paths, and
  knowledge paths must continue to reject absolute paths, `..` traversal, and
  symlink escapes before an agent or tool opens them.
- **MCP schemas:** new tool inputs need positive issue/PR ids, bounded outcome
  enums, bounded rationale strings, and explicit refusal for missing required
  evidence. Expected gate failures should return structured `{ok, error,
  message, next_action}` envelopes.
- **Secret handling:** documentation coverage tools must not publish full file
  contents, environment dumps, raw command output, tokens, prompts, or reviewer
  transcripts. Return changed paths, matched documentation targets, and compact
  rationale only.
- **OS/process exposure:** keep `gh`, `git`, and policy invocations argv-based.
  Do not put GitHub, SonarCloud, provider, or user tokens in process arguments,
  comments, telemetry, report fields, or error messages.
- **Backend boundary:** no backend REST, database, or frontend surface is
  required for this workflow gate. If later added, it must reuse Bean
  Validation, project scoping, `GroundControlException` through
  `GlobalExceptionHandler`/`ErrorResponse`, `ActorHolder`/`ActorFilter`, and the
  api/domain/infrastructure boundary.

## Extensibility Guardrails

- The useful seam is a documented "changed surface -> documentation target"
  classifier. It should be data-driven enough to add a new surface class, such
  as frontend API docs or another config section, without rewriting every
  workflow step and renderer.
- Keep the documentation outcome vocabulary small and explicit. Likely values
  are `updated`, `verified_unchanged`, and
  `not_updated_authorized`. Avoid ad hoc prose variants that policy and
  renderers cannot reason about.
- If the step participates in ADR-036 routing/telemetry, add one canonical
  stage id and update the routing defaults/tests once. Do not scatter the same
  stage name across skill prose, config examples, and tests independently.
- If multiple record surfaces need the same documentation outcome, share a pure
  validator/renderer helper at the MCP layer. Do not duplicate the same enum
  and text rules in the skill, Python policy, and JavaScript renderer.

## Gotchas and Anti-Patterns

- The existing `.ground-control.yaml` documentation is not enough for this
  issue's acceptance criteria unless it is reconciled against the parser's full
  accepted shape, including defaults, nested review-cap blocks, routing,
  telemetry, and architecture vocabulary.
- A `.yaml`, `.json`, policy, schema, or runnable code block inside a Markdown
  file can be executable behavior for this repo's tools. Do not classify it as
  "docs-only" just because the path ends in `.md`.
- Do not conflate documentation coverage with changelog fragments. A
  changelog fragment is release-note signal; it does not prove that config,
  API, workflow, or ADR docs are complete.
- Do not conflate ADR impact with documentation coverage. An ADR reference in a
  PR body is not evidence that the changed surface's user-facing or operator
  documentation was checked.
- Do not let final report `summary` carry the only documentation evidence. The
  renderer should expose a structured field or section so omission is visible.
- Do not add a broad natural-language style reviewer as the enforcement layer.
  Style can be reviewed, but coverage needs structural changed-surface mapping
  and deterministic report evidence.

## Non-Goals

- No new backend service, database table, JPA entity, REST controller, frontend
  UI, or exception hierarchy for documentation coverage.
- No second `.ground-control.yaml` parser, config schema, workflow DSL, local
  state file, git note, or database-backed workflow counter.
- No change to the one-human-touchpoint model, review-cycle caps, zero-deferral
  rule, or traceability reconciliation order.
- No formal Ground Control requirement is introduced for this issue-free run;
  issue #896 remains the authoritative contract.
