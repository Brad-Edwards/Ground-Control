# Test-Quality Clean Continuation Preflight

Issue #884 is a workflow-friction fix: `/implement` Step 13 can halt after
`/review-tests` reports no issues, even though a clean test-quality review is
supposed to be a pass-through into Step 14. This note is architecture preflight
guidance only. It does not implement the skill change.

## Architecture Boundary

- Preserve ADR-029's one synchronous touchpoint: PR merge. A clean
  test-quality review must not require a user acknowledgement turn.
- Keep `skills/implement/SKILL.md` as the canonical parent workflow and
  `skills/review-tests/SKILL.md` as the child review skill. Host installs under
  `~/.claude` or `~/.codex` are distribution targets only.
- Treat the GitHub issue thread as the durable record. Do not introduce local
  files, git notes, telemetry records, or driver-local state to remember that
  Step 13 was clean.
- Prefer the existing `gc_post_decision_record` contract for the structured
  clean signal: it already accepts `reviewer: "test-quality"` and
  `findings: []`, renders a `0 (clean run)` record, filters sensitive content,
  posts a `gc:decision-record` marker, and returns a structured envelope.
- The child skill's human-readable line (`Test quality review: no issues
  found`) may remain useful for humans, but it must not be the only signal the
  parent workflow relies on to advance.

## Cross-Cutting Concerns to Reuse

- **Repo/config resolution:** reuse `.ground-control.yaml` through
  `gc_get_repo_ground_control_context`; do not teach the review skill or parent
  workflow to parse base branches, docs paths, or quality commands a second
  way.
- **Durable records:** reuse `gc_post_decision_record`,
  `buildDecisionRecord`, `validateDecisionRecordInput`, and the
  `gc:decision-record` marker family in `mcp/ground-control/lib.js`.
- **GitHub posting boundary:** keep issue-comment writes in the MCP server via
  `ensureGitRepo`, `getOwnerRepo`, `gh api` argv execution, body-size checks,
  `detectSensitiveBodyContent`, and structured `*_post_failed` envelopes.
- **Review-loop vocabulary:** reuse ADR-029 dispositions (`fix`, `wontfix`,
  `not-applicable`) and the existing `test-quality` reviewer enum. A clean run
  is `findings: []`, not a synthetic finding with a pseudo-decision.
- **Policy/docs sync:** if the implementation changes workflow semantics or
  cap wording, keep `skills/implement/SKILL.md`,
  `skills/review-tests/SKILL.md`, `docs/DEVELOPMENT_WORKFLOW.md`,
  `docs/WORKFLOW.md`, ADR-029/ADR-036, and policy guardrails consistent.

## Security Layers In Scope

- **MCP argument schema:** `issueNumber` and `cycle` stay positive integers;
  `reviewer` must be the existing `test-quality` enum value; `findings` is an
  array that may be empty. Do not add an unvalidated string sentinel as a tool
  argument.
- **Secret handling:** no raw review transcript, issue body, command output, or
  environment data should be posted for a clean run. If a durable comment is
  posted, it must pass through `detectSensitiveBodyContent`.
- **Marker integrity:** caller-controlled text must still reject reserved
  `<!-- gc:` marker prefixes. A clean result should not expand the marker parser
  surface with a second marker family unless the existing decision-record marker
  cannot represent the state.
- **OS/process exposure:** keep GitHub writes behind `gh api` argv-style calls
  in the MCP server. Do not pass tokens, Sonar credentials, or provider keys in
  argv, issue comments, PR bodies, or telemetry.
- **Error envelopes:** expected failures use the existing structured envelope
  style (`ok`, `error`, `message`, `next_action`) rather than thrown control
  flow or user-visible stack traces.

## Extensibility Guardrail

The seam should be "reviewer outcome as structured cycle record." Today the
obvious value is a clean `test-quality` review with `findings: []`. A future
reviewer or future richer outcome (`skipped`, `blocked`, `clean`) should extend
the same reviewer/cycle record shape or add a parameter to that canonical
record surface, not create one marker or parser per reviewer.

## Gotchas and Anti-Patterns

- Do not add a new `gc:test-quality-review-clean` marker while
  `gc_post_decision_record` already models zero findings for
  `reviewer: "test-quality"`; that would create duplicate durable-state
  schemas.
- Do not make the parent parse loose prose such as "no issues found" as the
  durable contract. Prose may change for readability; the structured record
  should carry the workflow signal.
- Do not make the child skill responsible for advancing Step 14. The parent
  `/implement` workflow owns phase progression; the child review skill reports
  the review outcome.
- Do not use telemetry or skill-call logs as compliance evidence. They are
  operational aids, not ADR-029 durable records.
- Step 13 currently has cap wording drift: the shared review-loop text and
  docs reference ADR-029's three-cycle reviewer cap, while Step 13 still says
  five iterations. Issue #884 does not require changing caps, but any edit near
  this prose must avoid preserving or widening that inconsistency silently.

## Non-Goals

- No new workflow engine, Temporal state, database table, local state file, or
  GitHub client abstraction.
- No change to ADR-029's one-human-touchpoint model, zero-deferral rule, issue
  thread durable-record model, or PR-merge ownership.
- No change to the substance of the test-quality review rubric.
- No broad rewrite of `/implement`, `/review-tests`, routing, telemetry, CI,
  SonarCloud, traceability reconciliation, or final reporting.
