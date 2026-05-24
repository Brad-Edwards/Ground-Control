# GitHub Update Succinctness Preflight

Issue #964 constrains `/implement` and `/quickfix` GitHub update length:
plans, review decision records, final reports, PR bodies, and review findings
should give exactly what the reader needs, with no restated context, padding
sections, or hedging prose. This note is architecture preflight guidance only.
It does not implement skill edits, renderer changes, tests, or policy updates.

## Architecture Boundaries

- Keep ADR-029's issue-thread durable-record model unchanged. The issue asks
  for shorter updates, not a new record type, marker family, workflow gate, or
  human touchpoint.
- Keep ADR-036's deterministic renderer boundary. `gc_post_decision_record`,
  `gc_post_final_report`, and `gc_render_pr_body` remain structured-input
  renderers; agents should not bypass them with free-form `gh issue comment`
  or hand-authored PR bodies.
- Treat succinctness as a rendering and workflow-surface contract, not a new
  domain abstraction. Do not introduce a "verbosity service", separate schema,
  prompt DSL, local state file, database table, or policy engine for this.
- Define the canonical rule once in workflow prose and reference it from the
  per-step files and `/quickfix` surface: "a GitHub update gives exactly what's
  needed - not more, not less. No restating context the reader already has, no
  padding sections, no hedging prose." Avoid near-duplicate variants that drift.
- Do not weaken existing safety text. Shorter comments must still preserve
  no-deferral language, sensitive-content filtering, phase/decision/final-report
  markers, CI/Sonar status evidence, traceability summaries, and PR-body policy
  sections required by `check_pr_body`.

## Cross-Cutting Concerns to Reuse

- **Tool schemas and validation:** update the existing Zod schemas in
  `mcp/ground-control/index.js` and pure validators in
  `mcp/ground-control/lib.js`. Add hard caps only where the field is naturally
  summarizing: PR-body `summary` and final-report `summary` are the obvious
  seams. Keep caps as named constants beside the renderer validators so tests
  can assert them.
- **Deterministic renderers:** tighten `buildDecisionRecord`,
  `buildFinalReport`, `buildQuickfixCloseComment`, and `buildPrBody`. Drop
  optional sections when inputs are empty instead of emitting placeholder
  sections, unless a downstream policy gate requires the section or marker.
- **GitHub posting boundary:** keep using `ensureGitRepo`, `getOwnerRepo`,
  `detectSensitiveBodyContent`, reserved-marker rejection, GitHub body-size
  checks, and argv-style `gh api` posting in `mcp/ground-control/lib.js`.
- **PR-body policy:** preserve `tools/policy/checks.py::check_pr_body` and
  `tools/tests/test_policy.py` as the PR-body compose contract. If the renderer
  gets leaner, its output must still pass the Python policy check.
- **Workflow skills:** keep `skills/implement/SKILL.md` as the orchestrator and
  `skills/implement/steps/_review-loop-rules.md` as the review-loop source of
  truth. Step files should reference the rule, not carry bespoke prose about
  what "succinct" means.
- **Docs/ADR sync:** if workflow-surface text changes, keep
  `docs/DEVELOPMENT_WORKFLOW.md`, `docs/WORKFLOW.md`, and
  `architecture/policies/adr-policy.json` in sync where existing policy expects
  workflow guardrail parity. Run `make policy` before declaring the
  implementation complete.

## Security and Validation Layers

- **MCP argument schemas:** length caps must reject oversized summary fields
  before rendering. Refusals should return the existing `ok: false`, `error`,
  `message`, `issue_number`, and `next_action` envelope shape rather than
  throwing for expected caller mistakes.
- **Sensitive content:** concise rendering does not replace
  `detectSensitiveBodyContent`. Any model- or agent-controlled text that lands
  in an issue comment or PR body still passes the existing secret detector.
- **Reserved marker protection:** caller-controlled fields must keep the
  `<!-- gc:` rejection path so a short summary cannot forge phase,
  decision-record, or final-report markers.
- **OS/process exposure:** GitHub side effects stay in the MCP server with argv
  arrays. Do not route shorter update text through Codex/Claude sandbox calls to
  `gh`, `git`, or `curl`, and do not put tokens or raw transcripts in process
  arguments.
- **Policy gates:** PR-body section removal is allowed only when
  `check_pr_body` does not require the section. Required Ground Control
  sections, IMPLEMENTS/TESTS markers, no-deferral checks, and changelog
  assertions remain intact.
- **Error leakage:** do not include raw prompts, raw diffs, raw CI logs,
  environment dumps, or reviewer transcripts in validation errors just to
  explain a length refusal.

## Maintainability and Extensibility

- The extensibility seam is a small set of named renderer limits, not scattered
  prose. Start with PR-body summary and final-report summary caps; if another
  field later proves noisy, add a named cap near that field's validator and
  cover it with renderer tests.
- Keep optional-section behavior declarative in each renderer: required policy
  sections always render; optional sections render only when their structured
  input is non-empty. This makes future lean sections a local renderer change,
  not a skill-wide rewrite.
- Tool descriptions should say "be succinct" because they are caller-facing
  contracts, but enforcement belongs in validators and renderer tests where the
  shape is deterministic.
- Review findings records are partly reviewer-produced. Keep raw findings
  durable enough for audit, but make summaries and decision records lean; do
  not truncate evidence silently.

## Gotchas and Anti-Patterns

- Do not conflate "short" with "missing required evidence." A final report
  still needs CI/Sonar status and PR readiness; a PR body still needs policy
  sections; a decision record still needs every finding's disposition.
- Do not add placeholder text such as "None", "N/A", or "No changes" in
  optional sections that can simply be omitted. Keep placeholders only where a
  policy gate or durable-record reader depends on an explicit marker.
- Do not create duplicate succinctness rubrics in every step file. The more
  variants exist, the less likely agents and tools will converge.
- Do not use broad natural-language trimming that can delete security,
  traceability, or no-deferral evidence. Renderer logic should be structural.
- Do not change review caps, phase ordering, lane selection, requirement
  transitions, traceability reconciliation, or PR merge ownership as part of
  this issue.

## Non-Goals

- No new ADR is required for this issue unless implementation changes durable
  record semantics. The intended change is update length only.
- No backend REST API, JPA entity, frontend UI, configuration property, or
  database migration.
- No new GitHub client, marker family, workflow state store, exception
  hierarchy, or policy engine.
- No rewrite of reviewer prompts beyond the minimum needed to keep generated
  summaries and findings records lean.
