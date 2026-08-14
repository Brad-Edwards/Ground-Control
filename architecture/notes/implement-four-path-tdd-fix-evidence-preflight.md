# Four-Path TDD and Review-Fix Evidence Preflight

Issue #871 strengthens the `/implement` development method with an explicit
bug-fix red-green variant and a rule that executable review fixes lock
themselves with regression evidence. This note is architecture guidance only;
it does not implement those workflow changes.

## Binding Architecture Decisions

- Treat this as an incremental amendment to GC-O007 and ADR-021. It adds no
  workflow phase, reviewer, synchronous approval, configuration knob, marker
  family, or `/bugfix` lane.
- Put operative TDD guidance in
  `skills/implement/steps/step-04.4-tdd.md`. The main
  `skills/implement/SKILL.md` is now a thin orchestrator and must not regain
  step prose.
- Put rules shared by Codex and test-quality review in
  `skills/implement/steps/_review-loop-rules.md`. Step 6.5 and Step 6.6 point
  to that canonical file; do not copy a fix-evidence rule into one reviewer
  step only.
- Keep issue intent and test discipline distinct. An informational
  `implementation_intent` value (`feature`, `bug-fix`, or `mixed`) may be
  derived from the issue labels and body already returned by
  `gc_implement_mechanical action=bootstrap`. The plan remains dispositive:
  it assigns the applicable TDD path per clause or acceptance criterion, so a
  heuristic match cannot waive a red-green obligation.
- Keep that intent value ephemeral. If it is cached between semantic steps,
  it is workflow context, not a new `.ground-control.yaml` field, durable
  marker, telemetry dimension, requirement attribute, or GitHub label.
- Preserve the existing documentation-only carve-out exactly. Any executable
  code, runtime-consumed config, schema, grammar, fixture, or policy data
  disqualifies it. Pure renaming or defensive narrowing in executable code is
  not documentation-only and must not be moved into the carve-out merely
  because behavior is intended to remain unchanged.
- A shipped-code bug fix uses the unmodified buggy tree as the red state. The
  regression test must fail for the reported defect, not for test wiring,
  unavailable infrastructure, or an unrelated assertion. If the defect
  cannot be reproduced at a meaningful boundary, investigation continues;
  the implementation must not substitute a snapshot or prose assertion.
- A review finding fixed in executable code or a runtime-consumed data
  contract needs proportionate regression evidence that fails when the named
  defect is reintroduced. Pure prose may state that there is no executable
  surface to lock. Any other exception must remain narrowly factual and must
  not broaden the Step 4.4 documentation-only carve-out.

## Durable-Record Boundary

The issue's proposed “decision-record rationale cites the test” rule does not
fit the current cycle contract. `gc_codex_review_cycle` and
`gc_test_quality_review_cycle` call `buildAutoFixDecisionFindings` and
auto-post `gc_post_decision_record` before the agent applies fixes. The
rationale therefore describes the finding and the decision to fix it; it
cannot truthfully cite post-fix test evidence.

The implementation must choose one honest contract:

- Keep #871 prose-and-policy scoped. Require the agent to add and run the
  regression evidence during the fix loop, but do not claim that the existing
  auto-posted decision record proves the post-fix test.
- If durable per-finding test evidence is mandatory acceptance, explicitly
  expand the issue to change the MCP record lifecycle and schema. Reuse the
  existing decision-finding object, validator, renderer, sensitive-content
  checks, and marker family. Do not add a second free-prose comment path or a
  parallel finding-resolution schema.

Silently keeping the old auto-rationale while documenting it as test evidence
would make the GitHub issue thread misleading, which is worse than leaving the
evidence advisory.

## Cross-Cutting Concerns to Reuse

- **Issue input:** reuse the issue object returned by
  `gc_implement_mechanical action=bootstrap` and the cached
  `gc_get_issue_thread` path. Do not fetch the issue through direct `gh`, add
  another cache, or duplicate issue-body parsing.
- **TDD contract:** extend `step-04.4-tdd.md` without weakening its existing
  clause-by-clause loop, structural-gate definition, security-behavior tests,
  or actual-diff carve-out revalidation.
- **Review loop:** extend `_review-loop-rules.md`; preserve its one-off versus
  class finding model, zero-deferral rule, proportionate self-verification,
  cap dispatch, and local-only restaging behavior.
- **Durable records:** retain `runPostDecisionRecord`,
  `validateDecisionRecordInput`, `buildDecisionRecord`, reserved-marker
  rejection, `detectSensitiveBodyContent`, GitHub body-size checks,
  `ensureGitRepo`, `getOwnerRepo`, and the MCP-owned `gh api` posting path.
- **Policy:** extend `run_implement_execution_contract` in
  `tools/policy/execution_contract.py` and its existing regression shard
  `tools/tests/test_policy_implement_execution.py`. The historical
  `tools/tests/test_policy.py` target no longer exists.
- **Workflow synchronization:** let `workflow-guardrail-sync` require both
  workflow documents and one relevant gate-model record. ADR-021 is the
  relevant record for this development-method amendment; ADR-029, ADR-031,
  and ADR-036 need no contentless amendments unless their contracts change.
- **Release model:** Release Please owns `CHANGELOG.md`; changelog fragments
  were retired by issue #1399. The eventual PR uses a Conventional Commit
  title instead of adding `changelog.d/871.changed.md`.

## Security and Validation Layers

- **Untrusted issue text:** labels, headings, and keywords are data used only
  for an informational hint. They never select a repository, authorize a
  side effect, relax TDD, spend a review cycle, or interpolate into a command.
- **MCP input shapes:** no Zod or `.ground-control.yaml` schema change is
  needed for agent-local intent. If durable fix evidence is later added, the
  tool Zod shape and `validateDecisionRecordInput` must change together; a
  second validator with different rules is forbidden.
- **Repository and GitHub authorization:** all issue reads and writes remain
  behind absolute-repository validation, the origin-derived owner/repository
  identity, and MCP server ownership of privileged GitHub side effects. No
  agent-side `gh`, `git`, or `curl` path is introduced.
- **Secret and marker handling:** any future caller-controlled evidence sent
  to the issue thread must pass the existing reserved `<!-- gc:` rejection,
  secret scan, field bounds, and 65,535-byte body boundary before network I/O.
- **OS exposure:** the existing poster uses `execFile` with an argv array, not
  a shell. Test paths and describe-block names are non-secret bounded
  metadata; issue bodies, tokens, environment dumps, and test output must not
  be copied into argv or durable comments.
- **Error envelopes:** expected validation or posting failures retain stable
  `ok`, `error`, `message`, and `next_action` results. Do not add an exception
  hierarchy or turn an expected evidence failure into an uncaught process
  error.
- **Observability and persistence:** the plan and existing review records on
  the issue thread remain the audit surface. The informational intent cache is
  neither telemetry nor durable workflow state.

## Maintainability and Extensibility

- Name the two concepts differently: `implementation_intent` for the
  issue-level hint and `tdd_path` for each clause or finding repair. Do not
  overload the existing review finding `classification` (`one-off` or
  `class`) or PR `change_class` (`source` or `doc-only`).
- Express the four TDD paths as one table or parallel subsections in the
  canonical TDD step. A future fifth path can extend that one vocabulary
  without another skill or configuration schema.
- Policy should check structural anchors and semantics, not pin an entire
  sentence byte for byte. Require all path identities, the fail-before-fix
  bug invariant, the no-doc-carve-out invariant, and the executable-fix
  regression-evidence invariant. Add a mutation-style negative test showing
  that removing each load-bearing anchor produces a stable policy violation.
- If automated fix evidence becomes a later requirement, the extension seam
  is the existing decision-finding shape and review-cycle seam. It must be
  versioned around the current posting order rather than introduced as a new
  reviewer, marker counter, local file, or configuration toggle.

## Gotchas and Anti-Patterns

- Issue #871 predates the thin-orchestrator split. Editing only
  `skills/implement/SKILL.md`, or restating Step 4.4 and Step 6.5 there, would
  create duplicate workflow logic.
- Its cap-3 and post-PR Step 13 language is stale. Current defaults are one
  pre-push cycle per reviewer, and test-quality review is Step 6.6 before
  push. This work must not change caps or review placement.
- Its changelog-fragment acceptance criterion is retired. Do not recreate the
  removed Towncrier surface.
- Do not equate “requirement-free” with “bug fix.” Refactors and maintenance
  may also have no requirement, while a bug fix may implement or amend an
  in-scope requirement.
- Do not let keyword matching become a gate. Quoted text, historical context,
  or an out-of-scope section may contain “broken” or “regression.” Per-clause
  plan classification is the correction mechanism.
- Do not use substring tests that merely prove workflow prose contains its own
  wording. The repo-native policy contract should detect structural drift; it
  does not prove that a particular runtime fix was regression-tested.
- Do not conflate a no-new-test rationale with the documentation-only TDD
  carve-out. The former explains evidence for one review repair; the latter
  is a whole-diff exception with existing plan, issue-thread, structural-gate,
  and actual-diff prerequisites.

## Non-Goals

- No separate `/bugfix` skill or alias.
- No new MCP review tool, reviewer type, workflow phase, human touchpoint,
  cycle cap, cap override, or auto-disposition behavior.
- No backend, database, graph, Temporal, local-state, or telemetry change.
- No automated semantic bug classifier and no configurable keyword list.
- No backfill of historical review decisions or tests.

## Design Vocabulary That Applies

- **Pattern, Issue-thread record:** the plan and current review records remain
  GitHub issue comments; no parallel durable store is introduced.
- **Canonical helper, `gh api` argv-based posting:** any GitHub mutation
  remains in the MCP server and reuses the existing posting boundary.
- **Boundary contract:** the MCP server owns privileged GitHub side effects;
  requirements and ADRs remain repo-local reviewed files.
- **Binding ADRs:** ADR-027 for the thin agent-neutral workflow package,
  ADR-029 for the issue thread as durable record, and ADR-031 if the decision
  record lifecycle is expanded. ADR-093 remains the requirement-file
  authority.
- **Anti-recommendations:** do not duplicate prompt-only workflow rules, do
  not introduce an abstraction for a single extra path, and do not invoke
  `gh`, `git`, or `curl` from an agent sandbox.
